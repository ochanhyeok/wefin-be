package com.solv.wefin.benchmark;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 잔고 차감 동시성: 비관적 락 vs 낙관적 락 vs 원자적 조건부 UPDATE 3-way 벤치마크.
 *
 * <p>WeFin 실제 매수 차감을 재현 — 실제 테이블 {@code virtual_account} + {@code portfolio}에
 * 실제 순서(계좌 → 포트폴리오)로 단일 계좌 동시 매수를 날린다. 세 전략을 동일 부하로 비교한다.</p>
 *
 * <ul>
 *   <li><b>비관적</b>: {@code SELECT ... FOR UPDATE} 로 잠그고 읽어 앱에서 검증 후 차감.</li>
 *   <li><b>낙관적</b>: version 읽고 계좌+포트폴리오 둘 다 CAS, 한 행이라도 충돌 시 전체 롤백·재시도.</li>
 *   <li><b>원자적</b>: 잔고 가드를 WHERE 에 넣은 단일 {@code UPDATE ... WHERE balance >= cost}.
 *       read-then-write 자체가 없어 Lost Update 원천 차단.</li>
 * </ul>
 *
 * <h3>측정 방법</h3>
 * <ul>
 *   <li>실험 1 (처리량): 스레드 1/5/10/20/50 으로 경합도를 바꾼다. 잔고는 충분하게 둔다.</li>
 *   <li>실험 2 (정합성): 잔고를 정확히 {@value #AFFORDABLE}건만 살 수 있게 두고 그보다 많이 동시 요청한다.
 *       잔고 부족 가드가 실제로 발동하는 상황에서 초과 매수·음수 잔고가 없는지 확인한다.</li>
 *   <li>측정 전 워밍업 1회를 버리고, 경합도마다 전략 실행 순서를 바꿔(rotate) 순서에 따른 편향을 줄인다.</li>
 *   <li>작업 스레드 예외·시간 초과는 모아서 실패로 처리한다. 정합성은 단언으로 검증한다.</li>
 * </ul>
 *
 * <p>결과 표는 콘솔과 {@code build/benchmark-results/lock-benchmark.md} 에 함께 남는다.</p>
 *
 * <h3>실행</h3>
 * <pre>{@code
 * ./gradlew benchmark --tests "*LockStrategyBenchmarkTest"
 * }</pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("benchmark")
@DisplayName("비관적 vs 낙관적 vs 원자적 동시성 벤치마크 (실제 스키마)")
class LockStrategyBenchmarkTest {

	enum Strategy {
		PESSIMISTIC("비관적"), OPTIMISTIC("낙관적"), ATOMIC("원자적");

		final String label;

		Strategy(String label) {
			this.label = label;
		}
	}

	private static final int[] THREAD_COUNTS = {1, 5, 10, 20, 50};
	private static final int OPS_PER_THREAD = 100;
	private static final long RICH_BALANCE = 1_000_000_000L;
	private static final long PRICE = 100;
	private static final int QTY = 1;
	private static final long COST = PRICE * QTY;
	/** 실험 2: 잔고로 살 수 있는 건수. 요청 수보다 적게 둬서 잔고 부족 가드를 발동시킨다 */
	private static final int AFFORDABLE = 37;
	private static final long RUN_TIMEOUT_SECONDS = 120;
	private static final Path REPORT = Path.of("build", "benchmark-results", "lock-benchmark.md");

	private static PostgreSQLContainer<?> postgres;
	private static String url, user, pass;
	private static final StringBuilder report = new StringBuilder();

	@BeforeAll
	static void setUp() throws Exception {
		postgres = new PostgreSQLContainer<>(
				DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
				.withDatabaseName("bench").withUsername("bench").withPassword("bench");
		postgres.start();
		url = postgres.getJdbcUrl(); user = postgres.getUsername(); pass = postgres.getPassword();

		try (Connection c = conn(); Statement st = c.createStatement()) {
			st.execute("CREATE TABLE virtual_account ("
					+ "virtual_account_id BIGINT PRIMARY KEY, balance NUMERIC(20,2) NOT NULL, version BIGINT NOT NULL DEFAULT 0)");
			st.execute("CREATE TABLE portfolio ("
					+ "portfolio_id BIGINT PRIMARY KEY, virtual_account_id BIGINT NOT NULL, stock_id BIGINT NOT NULL, "
					+ "quantity INTEGER NOT NULL, avg_price NUMERIC(20,2) NOT NULL, total_buy_amount NUMERIC(20,2) NOT NULL, "
					+ "version BIGINT NOT NULL DEFAULT 0)");
		}
		report.append("# 락 전략 벤치마크 결과\n\n")
				.append("- 단일 계좌에 동시 매수. 스레드당 ").append(OPS_PER_THREAD).append("건, 1건당 ")
				.append(COST).append("원\n")
				.append("- 처리량 = 성공 건수 / 전체 소요 시간. 지연시간은 요청 1건(재시도 포함)의 시간\n");
	}

	@AfterAll
	static void tearDown() throws Exception {
		Files.createDirectories(REPORT.getParent());
		Files.writeString(REPORT, report.toString());
		System.out.println("\n결과 표 저장: " + REPORT.toAbsolutePath());
		if (postgres != null) postgres.stop();
	}

	@Test
	@Order(1)
	@DisplayName("실험 1: 경합도(스레드 수)별 처리량·지연 비교")
	void throughputByContention() throws Exception {
		// 워밍업: JIT·커넥션·버퍼 캐시를 데운다. 결과는 버린다
		for (Strategy s : Strategy.values()) {
			run(5, 20, s, RICH_BALANCE);
		}

		StringBuilder t = new StringBuilder("\n## 실험 1) 경합도별 처리량 (잔고 충분)\n\n")
				.append("| 스레드 | 전략 | 처리량 (ops/s) | p50 (ms) | p95 (ms) | p99 (ms) | 재시도 | 정합성 |\n")
				.append("|---:|---|---:|---:|---:|---:|---:|---|\n");
		Strategy[] strategies = Strategy.values();
		for (int i = 0; i < THREAD_COUNTS.length; i++) {
			int threads = THREAD_COUNTS[i];
			List<Result> results = new ArrayList<>();
			for (int k = 0; k < strategies.length; k++) {
				Strategy s = strategies[(i + k) % strategies.length];
				results.add(run(threads, OPS_PER_THREAD, s, RICH_BALANCE));
			}
			results.sort((a, b) -> a.strategy.compareTo(b.strategy));
			for (Result r : results) {
				t.append(String.format("| %d | %s | %,d | %.2f | %.2f | %.2f | %,d | %s |%n",
						threads, r.strategy.label, r.throughput(), r.pct(50), r.pct(95), r.pct(99),
						r.retries, r.consistent ? "OK" : "실패"));
				assertThat(r.consistent).as("%s %d스레드 정합성", r.strategy.label, threads).isTrue();
				assertThat(r.success).as("%s %d스레드 성공 건수", r.strategy.label, threads)
						.isEqualTo(threads * OPS_PER_THREAD);
			}
		}
		System.out.print(t);
		report.append(t);
	}

	@Test
	@Order(2)
	@DisplayName("실험 2: 잔고 부족 경계 — 초과 매수·음수 잔고가 없어야 한다")
	void balanceGuardUnderContention() throws Exception {
		int threads = 20;
		int opsPerThread = 5;
		long balance = AFFORDABLE * COST;

		StringBuilder t = new StringBuilder("\n## 실험 2) 잔고 부족 경계 (").append(AFFORDABLE)
				.append("건 분량 잔고에 ").append(threads * opsPerThread).append("건 동시 요청)\n\n")
				.append("| 전략 | 성공 | 잔고 부족 거절 | 최종 잔고 | 최종 수량 | 정합성 |\n")
				.append("|---|---:|---:|---:|---:|---|\n");
		for (Strategy s : Strategy.values()) {
			Result r = run(threads, opsPerThread, s, balance);
			t.append(String.format("| %s | %d | %d | %,d | %d | %s |%n",
					s.label, r.success, threads * opsPerThread - r.success, r.finalBalance, r.finalQuantity,
					r.consistent ? "OK" : "실패"));
			assertThat(r.success).as("%s 성공 건수", s.label).isEqualTo(AFFORDABLE);
			assertThat(r.finalBalance).as("%s 최종 잔고", s.label).isZero();
			assertThat(r.finalQuantity).as("%s 최종 수량", s.label).isEqualTo(AFFORDABLE);
		}
		System.out.print(t);
		report.append(t);
	}

	private Result run(int threads, int opsPerThread, Strategy strategy, long initialBalance) throws Exception {
		reset(initialBalance);
		int totalOps = threads * opsPerThread;

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);

		long[] latencies = new long[totalOps];
		AtomicInteger idx = new AtomicInteger(0);
		AtomicInteger success = new AtomicInteger(0);
		AtomicLong retries = new AtomicLong(0);
		Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

		for (int t = 0; t < threads; t++) {
			pool.submit(() -> {
				try (Connection c = conn()) {
					c.setAutoCommit(false);
					ready.countDown();
					start.await();
					for (int i = 0; i < opsPerThread; i++) {
						long t0 = System.nanoTime();
						boolean ok = switch (strategy) {
							case PESSIMISTIC -> buyPessimistic(c);
							case OPTIMISTIC -> buyOptimistic(c, retries);
							case ATOMIC -> buyAtomic(c);
						};
						latencies[idx.getAndIncrement()] = System.nanoTime() - t0;
						if (ok) success.incrementAndGet();
					}
				} catch (Throwable e) {
					errors.add(e);
				} finally {
					done.countDown();
				}
			});
		}

		boolean finished;
		long wallNanos;
		try {
			// 준비 중(커넥션 획득 등) 실패한 작업은 ready 를 세지 않으므로, 여기서도 시간 제한을 둬야 멈추지 않는다
			boolean allReady = ready.await(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertThat(errors).as("%s %d스레드 준비 중 예외", strategy.label, threads).isEmpty();
			assertThat(allReady).as("%s %d스레드가 %d초 안에 준비돼야 한다", strategy.label, threads, RUN_TIMEOUT_SECONDS)
					.isTrue();
			long wallStart = System.nanoTime();
			start.countDown();
			finished = done.await(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			wallNanos = System.nanoTime() - wallStart;
		} finally {
			pool.shutdownNow();
		}

		assertThat(finished).as("%s %d스레드가 %d초 안에 끝나야 한다", strategy.label, threads, RUN_TIMEOUT_SECONDS)
				.isTrue();
		assertThat(errors).as("%s %d스레드 작업 중 예외", strategy.label, threads).isEmpty();

		long balance = currentBalance();
		int quantity = currentQuantity();
		boolean consistent = balance >= 0
				&& balance == initialBalance - (long) success.get() * COST
				&& quantity == success.get() * QTY;

		return new Result(strategy, success.get(), retries.get(), wallNanos,
				Arrays.copyOf(latencies, idx.get()), balance, quantity, consistent);
	}

	/** 비관적: 계좌 잠금→검증→차감→포트폴리오 잠금→수량 갱신 */
	private boolean buyPessimistic(Connection c) throws Exception {
		try {
			long bal;
			try (PreparedStatement ps = c.prepareStatement(
					"SELECT balance FROM virtual_account WHERE virtual_account_id = 1 FOR UPDATE");
				 ResultSet rs = ps.executeQuery()) {
				requireRow(rs, "virtual_account");
				bal = rs.getLong(1);
			}
			if (bal < COST) { c.rollback(); return false; }
			try (PreparedStatement ps = c.prepareStatement(
					"UPDATE virtual_account SET balance = balance - ? WHERE virtual_account_id = 1")) {
				ps.setLong(1, COST); ps.executeUpdate();
			}
			try (PreparedStatement ps = c.prepareStatement(
					"SELECT quantity FROM portfolio WHERE virtual_account_id = 1 AND stock_id = 1 FOR UPDATE");
				 ResultSet rs = ps.executeQuery()) {
				requireRow(rs, "portfolio");
			}
			updatePortfolio(c);
			c.commit();
			return true;
		} catch (Exception e) {
			c.rollback();
			throw e;
		}
	}

	/** 낙관적: 계좌+포트폴리오 version CAS, 하나라도 실패하면 전체 롤백 후 재시도 */
	private boolean buyOptimistic(Connection c, AtomicLong retries) throws Exception {
		while (true) {
			long bal, accVer, pfVer;
			try (PreparedStatement ps = c.prepareStatement(
					"SELECT balance, version FROM virtual_account WHERE virtual_account_id = 1");
				 ResultSet rs = ps.executeQuery()) {
				requireRow(rs, "virtual_account");
				bal = rs.getLong(1); accVer = rs.getLong(2);
			}
			try (PreparedStatement ps = c.prepareStatement(
					"SELECT version FROM portfolio WHERE virtual_account_id = 1 AND stock_id = 1");
				 ResultSet rs = ps.executeQuery()) {
				requireRow(rs, "portfolio");
				pfVer = rs.getLong(1);
			}
			if (bal < COST) { c.rollback(); return false; }

			int ua, ub;
			try (PreparedStatement ps = c.prepareStatement(
					"UPDATE virtual_account SET balance = balance - ?, version = version + 1 "
							+ "WHERE virtual_account_id = 1 AND version = ?")) {
				ps.setLong(1, COST); ps.setLong(2, accVer); ua = ps.executeUpdate();
			}
			try (PreparedStatement ps = c.prepareStatement(
					"UPDATE portfolio SET quantity = quantity + ?, total_buy_amount = total_buy_amount + ?, "
							+ "avg_price = ROUND((total_buy_amount + ?) / (quantity + ?), 2), version = version + 1 "
							+ "WHERE virtual_account_id = 1 AND stock_id = 1 AND version = ?")) {
				ps.setInt(1, QTY); ps.setLong(2, COST); ps.setLong(3, COST); ps.setInt(4, QTY);
				ps.setLong(5, pfVer); ub = ps.executeUpdate();
			}
			if (ua == 1 && ub == 1) { c.commit(); return true; }
			c.rollback();
			retries.incrementAndGet();
		}
	}

	/** 원자적: 잔고 가드를 WHERE 에 넣은 단일 UPDATE (read 없음 → Lost Update 원천 차단) */
	private boolean buyAtomic(Connection c) throws Exception {
		try {
			int ua;
			try (PreparedStatement ps = c.prepareStatement(
					"UPDATE virtual_account SET balance = balance - ? "
							+ "WHERE virtual_account_id = 1 AND balance >= ?")) {
				ps.setLong(1, COST); ps.setLong(2, COST); ua = ps.executeUpdate();
			}
			if (ua == 0) { c.rollback(); return false; }  // 잔고 부족 → 0행 갱신
			updatePortfolio(c);
			c.commit();
			return true;
		} catch (Exception e) {
			c.rollback();
			throw e;
		}
	}

	/** 매수 시 포트폴리오 수량/평단 갱신 (세 전략 공통) */
	private void updatePortfolio(Connection c) throws Exception {
		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE portfolio SET quantity = quantity + ?, total_buy_amount = total_buy_amount + ?, "
						+ "avg_price = ROUND((total_buy_amount + ?) / (quantity + ?), 2) "
						+ "WHERE virtual_account_id = 1 AND stock_id = 1")) {
			ps.setInt(1, QTY); ps.setLong(2, COST); ps.setLong(3, COST); ps.setInt(4, QTY);
			ps.executeUpdate();
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static void requireRow(ResultSet rs, String table) throws Exception {
		if (!rs.next()) {
			throw new IllegalStateException(table + " 행이 없음 — reset() 데이터 확인 필요");
		}
	}

	private void reset(long initialBalance) throws Exception {
		try (Connection c = conn(); Statement st = c.createStatement()) {
			st.execute("DELETE FROM portfolio");
			st.execute("DELETE FROM virtual_account");
			st.execute("INSERT INTO virtual_account (virtual_account_id, balance, version) VALUES (1, " + initialBalance + ", 0)");
			st.execute("INSERT INTO portfolio (portfolio_id, virtual_account_id, stock_id, quantity, avg_price, total_buy_amount, version) "
					+ "VALUES (1, 1, 1, 0, 0, 0, 0)");
		}
	}

	private long currentBalance() throws Exception {
		try (Connection c = conn(); Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery("SELECT balance FROM virtual_account WHERE virtual_account_id = 1")) {
			requireRow(rs, "virtual_account");
			return rs.getLong(1);
		}
	}

	private int currentQuantity() throws Exception {
		try (Connection c = conn(); Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery("SELECT quantity FROM portfolio WHERE virtual_account_id = 1 AND stock_id = 1")) {
			requireRow(rs, "portfolio");
			return rs.getInt(1);
		}
	}

	private static Connection conn() throws Exception {
		return DriverManager.getConnection(url, user, pass);
	}

	private static class Result {
		final Strategy strategy;
		final int success; final long retries; final long wallNanos;
		final long[] latencies; final long finalBalance; final int finalQuantity; final boolean consistent;

		Result(Strategy strategy, int success, long retries, long wallNanos, long[] latencies,
			   long finalBalance, int finalQuantity, boolean consistent) {
			this.strategy = strategy;
			this.success = success; this.retries = retries; this.wallNanos = wallNanos;
			this.latencies = latencies; this.finalBalance = finalBalance; this.finalQuantity = finalQuantity;
			this.consistent = consistent;
			Arrays.sort(this.latencies);
		}

		long throughput() { return wallNanos == 0 ? 0 : (long) (success / (wallNanos / 1_000_000_000.0)); }

		double pct(int p) {
			if (latencies.length == 0) return 0;
			int i = (int) Math.ceil(p / 100.0 * latencies.length) - 1;
			return latencies[Math.max(0, Math.min(i, latencies.length - 1))] / 1_000_000.0;
		}
	}
}
