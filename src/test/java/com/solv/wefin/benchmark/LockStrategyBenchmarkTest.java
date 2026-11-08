package com.solv.wefin.benchmark;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 *       read-then-write 자체가 없어 Lost Update 원천 차단(WeFin Flyway 로그인 잠금에 쓴 패턴).</li>
 * </ul>
 *
 * <p>스레드 1/5/10/20/50 으로 경합도를 바꿔 교차점을 본다. version 컬럼은 낙관적 비교용(프로덕션은 비관적).</p>
 */
@DisplayName("비관적 vs 낙관적 vs 원자적 동시성 벤치마크 (실제 스키마)")
class LockStrategyBenchmarkTest {

	enum Strategy { PESSIMISTIC, OPTIMISTIC, ATOMIC }

	private static final int[] THREAD_COUNTS = {1, 5, 10, 20, 50};
	private static final int OPS_PER_THREAD = 100;
	private static final long INITIAL_BALANCE = 1_000_000_000L;
	private static final long PRICE = 100;
	private static final int QTY = 1;
	private static final long COST = PRICE * QTY;

	private static PostgreSQLContainer<?> postgres;
	private static String url, user, pass;

	@BeforeAll
	static void setUp() throws Exception {
		postgres = new PostgreSQLContainer<>(
				DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
				.withDatabaseName("bench").withUsername("bench").withPassword("bench");
		postgres.start();
		url = postgres.getJdbcUrl(); user = postgres.getUsername(); pass = postgres.getPassword();

		try (Connection c = conn(); Statement st = c.createStatement()) {
			st.execute("CREATE TABLE virtual_account (" +
					"virtual_account_id BIGINT PRIMARY KEY, balance NUMERIC(20,2) NOT NULL, version BIGINT NOT NULL DEFAULT 0)");
			st.execute("CREATE TABLE portfolio (" +
					"portfolio_id BIGINT PRIMARY KEY, virtual_account_id BIGINT NOT NULL, stock_id BIGINT NOT NULL, " +
					"quantity INTEGER NOT NULL, avg_price NUMERIC(20,2) NOT NULL, total_buy_amount NUMERIC(20,2) NOT NULL, " +
					"version BIGINT NOT NULL DEFAULT 0)");
		}
	}

	@AfterAll
	static void tearDown() {
		if (postgres != null) postgres.stop();
	}

	@Test
	@DisplayName("경합도(스레드 수)별 3-way 비교")
	void compare() throws Exception {
		banner("WeFin 매수 차감 재현: 단일 계좌 동시 매수 — 비관적 vs 낙관적 vs 원자적");

		for (int threads : THREAD_COUNTS) {
			System.out.printf("%n▶ %d 스레드 (총 %,d건 차감)%n", threads, threads * OPS_PER_THREAD);
			Result p = runStrategy(threads, Strategy.PESSIMISTIC);
			Result o = runStrategy(threads, Strategy.OPTIMISTIC);
			Result a = runStrategy(threads, Strategy.ATOMIC);
			printRow("비관적", p);
			printRow("낙관적", o);
			printRow("원자적", a);
		}
		System.out.println("\n→ 저경합(1~5): 셋 다 비슷 / 고경합(20~50): 원자적·비관적이 낙관적(재시도 폭증)을 앞섬");
		System.out.println("  원자적은 read-then-write 가 없어 락 윈도우가 가장 짧음 → 보통 최고 처리량");
	}

	private void printRow(String name, Result r) {
		System.out.printf("   %-6s │ %,7d ops/s │ p95 %6.1fms │ %s │ %s%n",
				name, r.throughput(), r.p95Ms(),
				r.retries > 0 ? String.format("재시도 %,6d", r.retries) : "재시도      0",
				r.consistent ? "정합 OK" : "⚠ FAIL");
	}

	private Result runStrategy(int threads, Strategy strategy) throws Exception {
		reset();
		int totalOps = threads * OPS_PER_THREAD;

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);

		long[] latencies = new long[totalOps];
		AtomicInteger idx = new AtomicInteger(0);
		AtomicInteger success = new AtomicInteger(0);
		AtomicLong retries = new AtomicLong(0);

		for (int t = 0; t < threads; t++) {
			pool.submit(() -> {
				try (Connection c = conn()) {
					c.setAutoCommit(false);
					ready.countDown();
					start.await();
					for (int i = 0; i < OPS_PER_THREAD; i++) {
						long t0 = System.nanoTime();
						boolean ok = switch (strategy) {
							case PESSIMISTIC -> buyPessimistic(c);
							case OPTIMISTIC  -> buyOptimistic(c, retries);
							case ATOMIC      -> buyAtomic(c);
						};
						latencies[idx.getAndIncrement()] = System.nanoTime() - t0;
						if (ok) success.incrementAndGet();
					}
				} catch (Exception e) {
					throw new RuntimeException(e);
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		long wallStart = System.nanoTime();
		start.countDown();
		done.await(120, TimeUnit.SECONDS);
		long wallNanos = System.nanoTime() - wallStart;
		pool.shutdownNow();

		long balance = currentBalance();
		int quantity = currentQuantity();
		boolean consistent = balance == INITIAL_BALANCE - (long) success.get() * COST
				&& quantity == success.get() * QTY;

		return new Result(success.get(), retries.get(), wallNanos,
				Arrays.copyOf(latencies, idx.get()), consistent);
	}

	/** 비관적: 계좌 잠금→검증→차감→포트폴리오 잠금→수량 갱신 */
	private boolean buyPessimistic(Connection c) throws Exception {
		try {
			long bal;
			try (PreparedStatement ps = c.prepareStatement(
					"SELECT balance FROM virtual_account WHERE virtual_account_id = 1 FOR UPDATE");
				 ResultSet rs = ps.executeQuery()) {
				rs.next(); bal = rs.getLong(1);
			}
			if (bal < COST) { c.rollback(); return false; }
			try (PreparedStatement ps = c.prepareStatement(
					"UPDATE virtual_account SET balance = balance - ? WHERE virtual_account_id = 1")) {
				ps.setLong(1, COST); ps.executeUpdate();
			}
			try (PreparedStatement ps = c.prepareStatement(
					"SELECT quantity FROM portfolio WHERE virtual_account_id = 1 AND stock_id = 1 FOR UPDATE");
				 ResultSet rs = ps.executeQuery()) {
				rs.next();
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
				rs.next(); bal = rs.getLong(1); accVer = rs.getLong(2);
			}
			try (PreparedStatement ps = c.prepareStatement(
					"SELECT version FROM portfolio WHERE virtual_account_id = 1 AND stock_id = 1");
				 ResultSet rs = ps.executeQuery()) {
				rs.next(); pfVer = rs.getLong(1);
			}
			if (bal < COST) { c.rollback(); return false; }

			int ua, ub;
			try (PreparedStatement ps = c.prepareStatement(
					"UPDATE virtual_account SET balance = balance - ?, version = version + 1 " +
					"WHERE virtual_account_id = 1 AND version = ?")) {
				ps.setLong(1, COST); ps.setLong(2, accVer); ua = ps.executeUpdate();
			}
			try (PreparedStatement ps = c.prepareStatement(
					"UPDATE portfolio SET quantity = quantity + ?, total_buy_amount = total_buy_amount + ?, " +
					"avg_price = ROUND((total_buy_amount + ?) / (quantity + ?), 2), version = version + 1 " +
					"WHERE virtual_account_id = 1 AND stock_id = 1 AND version = ?")) {
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
					"UPDATE virtual_account SET balance = balance - ? " +
					"WHERE virtual_account_id = 1 AND balance >= ?")) {
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
				"UPDATE portfolio SET quantity = quantity + ?, total_buy_amount = total_buy_amount + ?, " +
				"avg_price = ROUND((total_buy_amount + ?) / (quantity + ?), 2) " +
				"WHERE virtual_account_id = 1 AND stock_id = 1")) {
			ps.setInt(1, QTY); ps.setLong(2, COST); ps.setLong(3, COST); ps.setInt(4, QTY);
			ps.executeUpdate();
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private void reset() throws Exception {
		try (Connection c = conn(); Statement st = c.createStatement()) {
			st.execute("DELETE FROM portfolio");
			st.execute("DELETE FROM virtual_account");
			st.execute("INSERT INTO virtual_account (virtual_account_id, balance, version) VALUES (1, " + INITIAL_BALANCE + ", 0)");
			st.execute("INSERT INTO portfolio (portfolio_id, virtual_account_id, stock_id, quantity, avg_price, total_buy_amount, version) " +
					"VALUES (1, 1, 1, 0, 0, 0, 0)");
		}
	}

	private long currentBalance() throws Exception {
		try (Connection c = conn(); Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery("SELECT balance FROM virtual_account WHERE virtual_account_id = 1")) {
			rs.next(); return rs.getLong(1);
		}
	}

	private int currentQuantity() throws Exception {
		try (Connection c = conn(); Statement st = c.createStatement();
			 ResultSet rs = st.executeQuery("SELECT quantity FROM portfolio WHERE virtual_account_id = 1 AND stock_id = 1")) {
			rs.next(); return rs.getInt(1);
		}
	}

	private static Connection conn() throws Exception {
		return DriverManager.getConnection(url, user, pass);
	}

	private static void banner(String title) {
		System.out.println("\n══════════════════════════════════════════════════════════════");
		System.out.println("  " + title);
		System.out.println("══════════════════════════════════════════════════════════════");
	}

	private static class Result {
		final int success; final long retries; final long wallNanos;
		final long[] latencies; final boolean consistent;

		Result(int success, long retries, long wallNanos, long[] latencies, boolean consistent) {
			this.success = success; this.retries = retries; this.wallNanos = wallNanos;
			this.latencies = latencies; this.consistent = consistent;
			Arrays.sort(this.latencies);
		}

		long throughput() { return wallNanos == 0 ? 0 : (long) (success / (wallNanos / 1_000_000_000.0)); }
		double p95Ms() { return pct(95); }
		double pct(int p) {
			if (latencies.length == 0) return 0;
			int i = (int) Math.ceil(p / 100.0 * latencies.length) - 1;
			return latencies[Math.max(0, Math.min(i, latencies.length - 1))] / 1_000_000.0;
		}
	}
}
