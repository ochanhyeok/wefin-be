package com.solv.wefin.benchmark;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 인덱스 유무에 따른 쿼리 성능 비교 벤치마크 (스케일별 추세 측정).
 *
 * <p>실제 wefin {@code orders} / {@code trade} 스키마와 동일한 테이블에 데이터를
 * <b>1만 / 10만 / 100만</b> 건으로 단계적으로 적재하고, 트레이딩 핵심 쿼리 3종을
 * 인덱스 추가 <b>전/후</b>로 {@code EXPLAIN ANALYZE} 하여 다음을 확인한다.</p>
 *
 * <ul>
 *   <li>{@code Seq Scan} → {@code Index Scan} 전환</li>
 *   <li>데이터가 커질수록 Seq Scan 은 선형 증가, Index Scan 은 거의 일정
 *       → 특정 건수가 아니라 <b>어느 규모든 인덱스가 유리</b>함을 입증</li>
 * </ul>
 *
 * <p>측정 안정화: 각 {@code EXPLAIN} 은 {@code ITERATIONS} 회 실행 후
 * 최소 실행시간(best-of-N)을 채택한다.</p>
 *
 * <p>Spring 컨텍스트 없이 순수 Testcontainers + JDBC 로 동작하므로 빠르고 독립적이다.</p>
 *
 * <h3>실행</h3>
 * <pre>{@code
 * ./gradlew test --tests "com.solv.wefin.benchmark.IndexBenchmarkTest" --info
 * }</pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("인덱스 유무 쿼리 성능 벤치마크 (스케일별)")
class IndexBenchmarkTest {

	/** 측정할 데이터 규모 — 한 점이 아니라 추세를 본다 */
	private static final int[] SCALES = {10_000, 100_000, 1_000_000};
	/** 노이즈 완화: 각 쿼리를 이만큼 반복 실행해 최소값 채택 */
	private static final int ITERATIONS = 3;

	private static PostgreSQLContainer<?> postgres;
	private static Connection conn;

	@BeforeAll
	static void setUp() throws Exception {
		postgres = new PostgreSQLContainer<>(
				DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
				.withDatabaseName("bench")
				.withUsername("bench")
				.withPassword("bench");
		postgres.start();
		conn = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

		// 지정가 매칭은 시세 틱마다 "단일 쿼리"로 실행되므로, 단일 쿼리 지연(latency)을 본다.
		// 병렬 스캔을 켜두면 인덱스 없는 쪽(Seq Scan)도 워커가 분담해 측정 배수가 왜곡되므로 끈다.
		exec("SET max_parallel_workers_per_gather = 0");
	}

	@AfterAll
	static void tearDown() throws Exception {
		if (conn != null) conn.close();
		if (postgres != null) postgres.stop();
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 실험 1: 지정가 매칭 쿼리 (WebSocket 시세 틱마다 FOR UPDATE 로 실행되는 핫패스)
	//   WHERE status IN ('PENDING','PARTIAL') AND order_type='LIMIT' AND stock_id=?
	// ─────────────────────────────────────────────────────────────────────────
	@Test
	@Order(1)
	@DisplayName("매칭 쿼리: 없음 vs 일반 인덱스 vs 부분 인덱스")
	void matchingQueryScaling() throws Exception {
		banner("실험 1) 지정가 매칭 쿼리  (없음 / 일반 복합 인덱스 / 부분 인덱스 3-way 비교)");
		String query = "SELECT * FROM orders " +
				"WHERE status IN ('PENDING','PARTIAL') AND order_type = 'LIMIT' AND stock_id = 42";

		List<TriRow> rows = new ArrayList<>();
		for (int n : SCALES) {
			boolean isTop = (n == SCALES[SCALES.length - 1]);
			System.out.println("\n▶ 스케일: orders " + fmt(n) + "건");

			recreateOrders();
			seedOrders(n);
			exec("ANALYZE orders");
			Measure none = explainBest("① 인덱스 없음", query, isTop);

			// A) 일반 복합 인덱스 — 모든 주문(체결/취소 포함)을 색인
			exec("CREATE INDEX idx_match_full ON orders (stock_id, order_type, status)");
			exec("ANALYZE orders");
			Measure full = explainBest("② 일반 복합 인덱스 (전체 색인)", query, isTop);
			long fullSize = indexSize("idx_match_full");
			exec("DROP INDEX idx_match_full");

			// B) 부분 인덱스 — 대기중/부분체결 주문만 색인
			exec("CREATE INDEX idx_match_partial ON orders (stock_id, order_type, status) " +
					"WHERE status IN ('PENDING','PARTIAL')");
			exec("ANALYZE orders");
			Measure partial = explainBest("③ 부분 인덱스 (대기중만 색인)", query, isTop);
			long partialSize = indexSize("idx_match_partial");
			exec("DROP INDEX idx_match_partial");

			rows.add(new TriRow(n, none, full, fullSize, partial, partialSize));
		}
		triSummary(rows);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 실험 2: 주문/체결 내역 커서(keyset) 페이징
	//   WHERE virtual_account_id=? ORDER BY trade_id DESC LIMIT 20
	// ─────────────────────────────────────────────────────────────────────────
	@Test
	@Order(2)
	@DisplayName("커서 페이징: 스케일별 Seq Scan+Sort -> Index Scan(정렬 제거)")
	void keysetPaginationScaling() throws Exception {
		banner("실험 2) 체결내역 커서 페이징");
		String query = "SELECT * FROM trade " +
				"WHERE virtual_account_id = 7 AND trade_id < 9223372036854775807 " +
				"ORDER BY trade_id DESC LIMIT 20";

		List<ScaleRow> rows = new ArrayList<>();
		for (int n : SCALES) {
			boolean isTop = (n == SCALES[SCALES.length - 1]);
			System.out.println("\n▶ 스케일: trade " + fmt(n) + "건");

			recreateTrade();
			seedTrade(n);
			exec("ANALYZE trade");
			Measure before = explainBest("BEFORE (인덱스 없음)", query, isTop);

			exec("CREATE INDEX idx_trade_acct_id ON trade (virtual_account_id, trade_id DESC)");
			exec("ANALYZE trade");
			Measure after = explainBest("AFTER  (복합 인덱스 idx_trade_acct_id)", query, isTop);

			rows.add(new ScaleRow(n, before, after));
		}
		summary("실험 2) 커서 페이징", rows);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 실험 3: 일일 수익 랭킹 집계
	//   WHERE side='SELL' AND created_at >= today AND realized_profit IS NOT NULL
	//   GROUP BY virtual_account_id ORDER BY SUM(realized_profit) DESC
	// ─────────────────────────────────────────────────────────────────────────
	@Test
	@Order(3)
	@DisplayName("랭킹 집계: 스케일별 전체 Seq Scan -> 당일 구간 Index Scan")
	void rankingAggregateScaling() throws Exception {
		banner("실험 3) 일일 수익 랭킹 집계  (trade 90일 분포)");
		String query = "SELECT virtual_account_id, SUM(realized_profit) AS profit " +
				"FROM trade " +
				"WHERE side = 'SELL' AND created_at >= NOW() - INTERVAL '1 day' " +
				"  AND realized_profit IS NOT NULL " +
				"GROUP BY virtual_account_id ORDER BY profit DESC LIMIT 10";

		List<ScaleRow> rows = new ArrayList<>();
		for (int n : SCALES) {
			boolean isTop = (n == SCALES[SCALES.length - 1]);
			System.out.println("\n▶ 스케일: trade " + fmt(n) + "건");

			recreateTrade();
			seedTrade(n);
			exec("ANALYZE trade");
			Measure before = explainBest("BEFORE (인덱스 없음)", query, isTop);

			exec("CREATE INDEX idx_trade_ranking ON trade (created_at, side) " +
					"WHERE realized_profit IS NOT NULL");
			exec("ANALYZE trade");
			Measure after = explainBest("AFTER  (부분 인덱스 idx_trade_ranking)", query, isTop);

			rows.add(new ScaleRow(n, before, after));
		}
		summary("실험 3) 랭킹 집계", rows);
	}

	// ─────────────────────────────────────── schema / seed ─────────────────────

	private static void recreateOrders() throws Exception {
		exec("DROP TABLE IF EXISTS orders");
		exec("CREATE TABLE orders (" +
				"order_id BIGSERIAL PRIMARY KEY," +
				"order_no UUID DEFAULT gen_random_uuid() NOT NULL," +
				"virtual_account_id BIGINT NOT NULL," +
				"stock_id BIGINT NOT NULL," +
				"order_type VARCHAR(10) NOT NULL," +
				"side VARCHAR(4) NOT NULL," +
				"quantity INTEGER NOT NULL," +
				"filled_quantity INTEGER DEFAULT 0 NOT NULL," +
				"request_price NUMERIC(15,2)," +
				"status VARCHAR(20) DEFAULT 'PENDING' NOT NULL," +
				"currency VARCHAR(3) DEFAULT 'KRW' NOT NULL," +
				"fee NUMERIC(15,2) NOT NULL," +
				"tax NUMERIC(15,2) NOT NULL," +
				"created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL," +
				"updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL)");
	}

	private static void recreateTrade() throws Exception {
		exec("DROP TABLE IF EXISTS trade");
		exec("CREATE TABLE trade (" +
				"trade_id BIGSERIAL PRIMARY KEY," +
				"trade_no UUID DEFAULT gen_random_uuid() NOT NULL," +
				"order_id BIGINT NOT NULL," +
				"virtual_account_id BIGINT NOT NULL," +
				"stock_id BIGINT NOT NULL," +
				"side VARCHAR(4) NOT NULL," +
				"quantity NUMERIC(15,2) NOT NULL," +
				"price NUMERIC(15,2) NOT NULL," +
				"total_amount NUMERIC(15,2) NOT NULL," +
				"fee NUMERIC(15,2) NOT NULL," +
				"tax NUMERIC(15,2) NOT NULL," +
				"realized_profit NUMERIC(15,2)," +
				"currency VARCHAR(3) DEFAULT 'KRW' NOT NULL," +
				"created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL)");
	}

	/**
	 * 주문 n건: 계좌 1~1000, 종목 1~100, 타입/방향 랜덤.
	 * 상태는 실제처럼 체결/취소 多, 대기중 少로 분포(약 FILLED 80% / CANCELLED 10% / PENDING 5% / PARTIAL 5%)
	 * → 부분 인덱스(대기중만 색인)가 일반 인덱스 대비 얼마나 작은지 드러나게 한다.
	 */
	private static void seedOrders(int n) throws Exception {
		exec("INSERT INTO orders " +
				"(order_no, virtual_account_id, stock_id, order_type, side, quantity, filled_quantity, " +
				" request_price, status, currency, fee, tax, created_at, updated_at) " +
				"SELECT gen_random_uuid(), (random()*1000)::bigint + 1, (random()*100)::bigint + 1, " +
				"  CASE WHEN random() < 0.5 THEN 'LIMIT' ELSE 'MARKET' END, " +
				"  CASE WHEN random() < 0.5 THEN 'BUY' ELSE 'SELL' END, " +
				"  1, 0, 10000, " +
				"  CASE WHEN s.r < 0.80 THEN 'FILLED' " +
				"       WHEN s.r < 0.90 THEN 'CANCELLED' " +
				"       WHEN s.r < 0.95 THEN 'PENDING' " +
				"       ELSE 'PARTIAL' END, " +
				"  'KRW', 0, 0, NOW(), NOW() " +
				"FROM (SELECT random() AS r FROM generate_series(1, " + n + ")) s");
	}

	/** 체결 n건: 계좌 1~1000, 90일 분포, 매도엔 실현손익 */
	private static void seedTrade(int n) throws Exception {
		exec("INSERT INTO trade " +
				"(trade_no, order_id, virtual_account_id, stock_id, side, quantity, price, total_amount, " +
				" fee, tax, realized_profit, currency, created_at) " +
				"SELECT gen_random_uuid(), 1, (random()*1000)::bigint + 1, (random()*100)::bigint + 1, " +
				"  CASE WHEN random() < 0.5 THEN 'SELL' ELSE 'BUY' END, " +
				"  1, 10000, 10000, 0, 0, " +
				"  CASE WHEN random() < 0.5 THEN (random()*200000 - 100000)::numeric(15,2) ELSE NULL END, " +
				"  'KRW', NOW() - make_interval(secs => (random()*7776000)::int) " +
				"FROM generate_series(1, " + n + ")");
	}

	// ─────────────────────────────────────── measure ───────────────────────────

	/**
	 * EXPLAIN (ANALYZE, BUFFERS) 를 ITERATIONS 회 실행해 최소 실행시간을 채택한다.
	 * printPlan=true 인 경우(보통 최대 스케일)에만 실행계획 전문을 1회 출력한다.
	 */
	private static Measure explainBest(String label, String query, boolean printPlan) throws Exception {
		double best = Double.MAX_VALUE;
		String scan = "?";
		for (int i = 0; i < ITERATIONS; i++) {
			boolean show = printPlan && i == 0;
			if (show) System.out.println("\n── " + label + " ──");
			double ms = -1;
			boolean indexed = false;
			try (Statement st = conn.createStatement();
				 ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + query)) {
				while (rs.next()) {
					String line = rs.getString(1);
					if (show) System.out.println("   " + line);
					if (line.contains("Index Scan") || line.contains("Index Only Scan")
							|| line.contains("Bitmap Index Scan")) {
						indexed = true;
					}
					if (line.trim().startsWith("Execution Time:")) {
						ms = Double.parseDouble(line.replaceAll("[^0-9.]", ""));
					}
				}
			}
			if (ms >= 0 && ms < best) best = ms;
			scan = indexed ? "Index" : "Seq";
		}
		double result = (best == Double.MAX_VALUE) ? -1 : best;
		System.out.printf("   %s → %s Scan, best %.3f ms (%d회 중 최소)%n", label, scan, result, ITERATIONS);
		return new Measure(result, scan);
	}

	private static void summary(String name, List<ScaleRow> rows) {
		System.out.println("\n┌──────────────── 스케일 추세: " + name + " ────────────────");
		System.out.printf ("│  %-12s %-18s %-18s %-10s%n", "건수", "BEFORE", "AFTER", "개선");
		for (ScaleRow r : rows) {
			double speedup = r.after.ms > 0 ? r.before.ms / r.after.ms : 0;
			System.out.printf("│  %-12s %-18s %-18s 약 %.0f배%n",
					fmt(r.n),
					String.format("%.3fms(%s)", r.before.ms, r.before.scan),
					String.format("%.3fms(%s)", r.after.ms, r.after.scan),
					speedup);
		}
		System.out.println("│  → BEFORE는 스캔량 따라 증가 / AFTER는 결과 행수 따라 변동");
		System.out.println("│    (절대 배수가 아니라 '같은 환경 상대 비교'로 해석 — 병렬 OFF, best-of-" + ITERATIONS + ")");
		System.out.println("└──────────────────────────────────────────────────────────────\n");
	}

	// ─────────────────────────────────────── util ──────────────────────────────

	private static void exec(String sql) throws Exception {
		try (Statement st = conn.createStatement()) {
			st.execute(sql);
		}
	}

	private static void banner(String title) {
		System.out.println("\n══════════════════════════════════════════════════════════════");
		System.out.println("  " + title);
		System.out.println("══════════════════════════════════════════════════════════════");
	}

	private static String fmt(int n) {
		return String.format("%,d", n);
	}

	/** 한 번의 측정 결과: 실행시간(ms) + 스캔 방식 */
	private record Measure(double ms, String scan) {}

	/** 한 스케일의 전/후 결과 */
	private record ScaleRow(int n, Measure before, Measure after) {}

	/** 실험1 3-way 결과: 없음 / 일반 인덱스(+크기) / 부분 인덱스(+크기) */
	private record TriRow(int n, Measure none, Measure full, long fullSize, Measure partial, long partialSize) {}

	/** 인덱스 디스크 크기(byte) */
	private static long indexSize(String idx) throws Exception {
		try (Statement st = conn.createStatement();
			 ResultSet rs = st.executeQuery("SELECT pg_relation_size('" + idx + "')")) {
			return rs.next() ? rs.getLong(1) : -1;
		}
	}

	private static String humanSize(long bytes) {
		if (bytes < 1024) return bytes + "B";
		if (bytes < 1024 * 1024) return String.format("%.0fKB", bytes / 1024.0);
		return String.format("%.1fMB", bytes / (1024.0 * 1024));
	}

	private static void triSummary(List<TriRow> rows) {
		System.out.println("\n┌────────────── 실험1) 매칭: 없음 vs 일반 vs 부분 ──────────────");
		System.out.printf ("│  %-10s %-12s %-22s %-22s%n", "건수", "①없음", "②일반(속도/크기)", "③부분(속도/크기)");
		for (TriRow r : rows) {
			System.out.printf("│  %-10s %-12s %-22s %-22s%n",
					fmt(r.n),
					String.format("%.2fms", r.none.ms),
					String.format("%.3fms / %s", r.full.ms, humanSize(r.fullSize)),
					String.format("%.3fms / %s", r.partial.ms, humanSize(r.partialSize)));
		}
		System.out.println("│  → 속도: 일반 ≈ 부분 (둘 다 인덱스 탐). 차이는 '크기' = 부분이 훨씬 작음");
		System.out.println("│    체결/취소 주문이 쌓여도 부분 인덱스는 안 커진다 = 부분 인덱스를 고른 이유");
		System.out.println("└──────────────────────────────────────────────────────────────\n");
	}
}
