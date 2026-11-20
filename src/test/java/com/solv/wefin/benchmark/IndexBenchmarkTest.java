package com.solv.wefin.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * 트레이딩 핫패스 인덱스 벤치마크 (스케일별, 재현 가능한 측정).
 *
 * <p>실제 wefin {@code orders} / {@code trade} 스키마에 <b>1만 / 10만 / 100만</b> 건을 적재하고,
 * 운영 코드와 <b>같은 모양의 쿼리</b>를 인덱스 전략별로 측정한다.</p>
 *
 * <ul>
 *   <li>실험 1 지정가 매칭: {@code OrderRepository.findAllByStatusInAndOrderTypeAndStockIdForUpdate} (FOR UPDATE 포함)</li>
 *   <li>실험 2 체결내역 첫 페이지: {@code TradeRepositoryImpl.searchTrades} (커서 없음, LIMIT size+1)</li>
 *   <li>실험 3 일일 랭킹: {@code RankingQueryRepositoryImpl.findDailySellAggregates} (KST 하루 범위)</li>
 * </ul>
 *
 * <h3>측정 방법</h3>
 * <ul>
 *   <li>데이터: {@code setseed} 로 난수를 고정해 누가 돌려도 같은 데이터가 생성된다.</li>
 *   <li>분포: 주문은 대기(PENDING+PARTIAL) 10% / 체결·취소 90%. 체결은 기준일까지 90일에 고르게 분포하고,
 *       실현손익은 매도 체결에만 존재한다.</li>
 *   <li>기준일 고정: 랭킹의 "오늘"을 {@link #REF_DAY} 로 고정한다. 실행 시각을 쓰면 자정 직후 실행 시
 *       당일 데이터가 거의 없어 측정이 왜곡된다.</li>
 *   <li>적재 후 {@code VACUUM ANALYZE} 로 통계와 visibility map 을 갱신한다.</li>
 *   <li>변형(인덱스 전략)마다 워밍업 {@value #WARMUP}회 후 {@value #RUNS}회 측정해 중앙값과 p95 를 기록한다.
 *       모든 변형이 같은 캐시 상태(워밍업 직후)에서 측정되므로 측정 순서에 따른 편향이 없다.</li>
 *   <li>실행시간은 {@code EXPLAIN (ANALYZE, TIMING OFF)} 의 서버 측 Execution Time 이다
 *       (노드별 타이밍 오버헤드 제거, 네트워크 왕복 제외).</li>
 *   <li>병렬 쿼리는 끈다. 매칭 쿼리는 FOR UPDATE 라 운영에서도 병렬 실행이 불가능하고,
 *       나머지도 단일 쿼리 지연을 같은 조건에서 비교하기 위해서다.</li>
 * </ul>
 *
 * <p>결과 표는 콘솔과 {@code build/benchmark-results/index-benchmark.md} 에 함께 남는다.</p>
 *
 * <h3>실행</h3>
 * <pre>{@code
 * ./gradlew benchmark --tests "*IndexBenchmarkTest"
 * }</pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("benchmark")
@DisplayName("인덱스 유무 쿼리 성능 벤치마크 (스케일별)")
class IndexBenchmarkTest {

	/** 측정할 데이터 규모 — 한 점이 아니라 추세를 본다. 공식 수치는 최대 규모 기준 */
	private static final int[] SCALES = {10_000, 100_000, 1_000_000};
	private static final int WARMUP = 5;
	private static final int RUNS = 30;
	/** 데이터 생성 난수 시드 — 실행할 때마다 같은 데이터 */
	private static final double SEED = 0.42;

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	/** 랭킹 집계 기준일 — 실행 시각과 무관하게 같은 "하루" 데이터를 집계하도록 고정한다 */
	private static final LocalDate REF_DAY = LocalDate.of(2026, 11, 20);
	private static final OffsetDateTime REF_DAY_START = REF_DAY.atStartOfDay(KST).toOffsetDateTime();
	private static final OffsetDateTime REF_DAY_END = REF_DAY_START.plusDays(1);
	private static final Path REPORT = Path.of("build", "benchmark-results", "index-benchmark.md");

	private static PostgreSQLContainer<?> postgres;
	private static Connection conn;
	private static final StringBuilder report = new StringBuilder();

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
		exec("SET max_parallel_workers_per_gather = 0");

		report.append("# 인덱스 벤치마크 결과\n\n")
				.append("- PostgreSQL: ").append(queryString("SHOW server_version")).append('\n')
				.append("- 측정: 워밍업 ").append(WARMUP).append("회 후 ").append(RUNS)
				.append("회, EXPLAIN (ANALYZE, TIMING OFF) Execution Time, 병렬 OFF\n")
				.append("- 데이터: setseed(").append(SEED).append("), 주문 대기 10% / 체결·취소 90%\n");
	}

	@AfterAll
	static void tearDown() throws Exception {
		Files.createDirectories(REPORT.getParent());
		Files.writeString(REPORT, report.toString());
		System.out.println("\n결과 표 저장: " + REPORT.toAbsolutePath());
		if (conn != null) conn.close();
		if (postgres != null) postgres.stop();
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 실험 1: 지정가 매칭 (시세 틱마다 실행되는 FOR UPDATE 핫패스)
	// ─────────────────────────────────────────────────────────────────────────
	@Test
	@Order(1)
	@DisplayName("매칭 쿼리: 없음 vs 일반 인덱스 vs 부분 인덱스")
	void matchingQuery() throws Exception {
		String query = "SELECT * FROM orders "
				+ "WHERE status IN ('PENDING','PARTIAL') AND order_type = 'LIMIT' AND stock_id = 42 "
				+ "FOR UPDATE";
		section("실험 1) 지정가 매칭 — FOR UPDATE, 없음 / 일반 복합 인덱스 / 부분 인덱스");

		for (int n : SCALES) {
			recreateOrders();
			seedOrders(n);
			vacuumAnalyze("orders");

			Variant none = measure("없음", query, null, true);
			Variant full = withIndex("일반 (stock_id, order_type, status)", query,
					"CREATE INDEX idx_match_full ON orders (stock_id, order_type, status)",
					"idx_match_full", true);
			Variant partial = withIndex("부분 WHERE status IN (PENDING, PARTIAL)", query,
					"CREATE INDEX idx_orders_match ON orders (stock_id, order_type, status) "
							+ "WHERE status IN ('PENDING', 'PARTIAL')",
					"idx_orders_match", true);
			table(n, null, List.of(none, full, partial));
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 실험 2: 체결내역 첫 페이지 (커서 없음, LIMIT size+1)
	// ─────────────────────────────────────────────────────────────────────────
	@Test
	@Order(2)
	@DisplayName("체결내역 페이징: 없음 vs (virtual_account_id, trade_id DESC)")
	void tradeFirstPage() throws Exception {
		String typical = "SELECT * FROM trade WHERE virtual_account_id = 7 "
				+ "ORDER BY trade_id DESC LIMIT 21";
		// 체결이 없는 계좌: PK 역순 스캔으로는 21건을 끝내 못 채워 테이블 끝까지 훑는 최악의 경우
		String noTrades = "SELECT * FROM trade WHERE virtual_account_id = 1001 "
				+ "ORDER BY trade_id DESC LIMIT 21";
		section("실험 2) 체결내역 첫 페이지 — 계좌 필터 + trade_id 역순");

		for (int n : SCALES) {
			recreateTrade();
			seedTrade(n);
			vacuumAnalyze("trade");

			Variant typicalNone = measure("없음 — 평균 계좌", typical, null, false);
			Variant emptyNone = measure("없음 — 체결 없는 계좌", noTrades, null, false);
			exec("CREATE INDEX idx_trade_acct_id ON trade (virtual_account_id, trade_id DESC)");
			vacuumAnalyze("trade");
			Variant typicalIdx = measure("(virtual_account_id, trade_id DESC) — 평균 계좌", typical,
					"idx_trade_acct_id", false);
			Variant emptyIdx = measure("(virtual_account_id, trade_id DESC) — 체결 없는 계좌", noTrades,
					"idx_trade_acct_id", false);
			exec("DROP INDEX idx_trade_acct_id");
			table(n, "평균 계좌", List.of(typicalNone, typicalIdx));
			table(n, "체결 없는 계좌", List.of(emptyNone, emptyIdx));
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 실험 3: 일일 수익 랭킹 (KST 하루 범위 집계)
	//   컬럼 순서 비교: 현재 V54 (created_at, side) vs 등호 조건 우선 (side, created_at)
	// ─────────────────────────────────────────────────────────────────────────
	@Test
	@Order(3)
	@DisplayName("랭킹 집계: 없음 vs (created_at, side) vs (side, created_at)")
	void dailyRanking() throws Exception {
		String query = "SELECT virtual_account_id, SUM(realized_profit) AS profit, COUNT(trade_id) AS cnt "
				+ "FROM trade "
				+ "WHERE side = 'SELL' "
				+ "  AND created_at >= '" + REF_DAY_START + "' AND created_at < '" + REF_DAY_END + "' "
				+ "  AND realized_profit IS NOT NULL "
				+ "GROUP BY virtual_account_id ORDER BY profit DESC, cnt ASC";
		section("실험 3) 일일 수익 랭킹 — KST 하루(" + REF_DAY + ") 범위, 인덱스 컬럼 순서 비교");

		for (int n : SCALES) {
			recreateTrade();
			seedTrade(n);
			vacuumAnalyze("trade");

			Variant none = measure("없음", query, null, false);
			Variant timeFirst = withIndex("부분 (created_at, side) — 현재 V54", query,
					"CREATE INDEX idx_trade_ranking ON trade (created_at, side) WHERE realized_profit IS NOT NULL",
					"idx_trade_ranking", false);
			Variant sideFirst = withIndex("부분 (side, created_at)", query,
					"CREATE INDEX idx_trade_ranking_side ON trade (side, created_at) WHERE realized_profit IS NOT NULL",
					"idx_trade_ranking_side", false);
			table(n, null, List.of(none, timeFirst, sideFirst));
		}
	}

	// ─────────────────────────────────────── schema / seed ─────────────────────

	private static void recreateOrders() throws Exception {
		exec("DROP TABLE IF EXISTS orders");
		exec("CREATE TABLE orders ("
				+ "order_id BIGSERIAL PRIMARY KEY,"
				+ "order_no UUID DEFAULT gen_random_uuid() NOT NULL,"
				+ "virtual_account_id BIGINT NOT NULL,"
				+ "stock_id BIGINT NOT NULL,"
				+ "order_type VARCHAR(10) NOT NULL,"
				+ "side VARCHAR(4) NOT NULL,"
				+ "quantity INTEGER NOT NULL,"
				+ "filled_quantity INTEGER DEFAULT 0 NOT NULL,"
				+ "request_price NUMERIC(15,2),"
				+ "status VARCHAR(20) DEFAULT 'PENDING' NOT NULL,"
				+ "currency VARCHAR(3) DEFAULT 'KRW' NOT NULL,"
				+ "fee NUMERIC(15,2) NOT NULL,"
				+ "tax NUMERIC(15,2) NOT NULL,"
				+ "created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,"
				+ "updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL)");
	}

	private static void recreateTrade() throws Exception {
		exec("DROP TABLE IF EXISTS trade");
		exec("CREATE TABLE trade ("
				+ "trade_id BIGSERIAL PRIMARY KEY,"
				+ "trade_no UUID DEFAULT gen_random_uuid() NOT NULL,"
				+ "order_id BIGINT NOT NULL,"
				+ "virtual_account_id BIGINT NOT NULL,"
				+ "stock_id BIGINT NOT NULL,"
				+ "side VARCHAR(4) NOT NULL,"
				+ "quantity NUMERIC(15,2) NOT NULL,"
				+ "price NUMERIC(15,2) NOT NULL,"
				+ "total_amount NUMERIC(15,2) NOT NULL,"
				+ "fee NUMERIC(15,2) NOT NULL,"
				+ "tax NUMERIC(15,2) NOT NULL,"
				+ "realized_profit NUMERIC(15,2),"
				+ "currency VARCHAR(3) DEFAULT 'KRW' NOT NULL,"
				+ "created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL)");
	}

	/**
	 * 주문 n건: 계좌 1~1000, 종목 1~100, 지정가/시장가 반반.
	 * 상태는 FILLED 80% / CANCELLED 10% / PENDING 5% / PARTIAL 5% — 실제 서비스처럼 대부분 끝난 주문이다.
	 */
	private static void seedOrders(int n) throws Exception {
		exec("SELECT setseed(" + SEED + ")");
		exec("INSERT INTO orders "
				+ "(virtual_account_id, stock_id, order_type, side, quantity, filled_quantity, "
				+ " request_price, status, currency, fee, tax) "
				+ "SELECT (random()*999)::bigint + 1, (random()*99)::bigint + 1, "
				+ "  CASE WHEN random() < 0.5 THEN 'LIMIT' ELSE 'MARKET' END, "
				+ "  CASE WHEN random() < 0.5 THEN 'BUY' ELSE 'SELL' END, "
				+ "  1, 0, 10000, "
				+ "  CASE WHEN s.r < 0.80 THEN 'FILLED' "
				+ "       WHEN s.r < 0.90 THEN 'CANCELLED' "
				+ "       WHEN s.r < 0.95 THEN 'PENDING' "
				+ "       ELSE 'PARTIAL' END, "
				+ "  'KRW', 0, 0 "
				+ "FROM (SELECT random() AS r FROM generate_series(1, " + n + ")) s");
	}

	/** 체결 n건: 계좌 1~1000, 기준일까지 90일에 고르게 분포, 실현손익은 매도에만 */
	private static void seedTrade(int n) throws Exception {
		exec("SELECT setseed(" + SEED + ")");
		exec("INSERT INTO trade "
				+ "(order_id, virtual_account_id, stock_id, side, quantity, price, total_amount, "
				+ " fee, tax, realized_profit, currency, created_at) "
				+ "SELECT 1, (random()*999)::bigint + 1, (random()*99)::bigint + 1, s.side, "
				+ "  1, 10000, 10000, 0, 0, "
				+ "  CASE WHEN s.side = 'SELL' THEN (random()*200000 - 100000)::numeric(15,2) END, "
				+ "  'KRW', '" + REF_DAY_END + "'::timestamptz - make_interval(secs => (random()*7776000)::int + 1) "
				+ "FROM (SELECT CASE WHEN random() < 0.5 THEN 'SELL' ELSE 'BUY' END AS side "
				+ "      FROM generate_series(1, " + n + ")) s");
	}

	// ─────────────────────────────────────── measure ───────────────────────────

	/** 인덱스를 만들고 측정한 뒤 지운다. 변형끼리 서로 영향을 주지 않게 한다 */
	private static Variant withIndex(String label, String query, String ddl, String indexName,
									 boolean lockingQuery) throws Exception {
		exec(ddl);
		vacuumAnalyze(indexName.startsWith("idx_trade") ? "trade" : "orders");
		Variant v = measure(label, query, indexName, lockingQuery);
		exec("DROP INDEX " + indexName);
		return v;
	}

	/**
	 * 워밍업 후 RUNS 회 측정해 중앙값·p95 를 낸다.
	 * FOR UPDATE 쿼리는 실제처럼 트랜잭션 안에서 실행하고 매번 롤백해 락을 푼다.
	 */
	private static Variant measure(String label, String query, String indexName,
								   boolean lockingQuery) throws Exception {
		String plan = explainPlan(query, lockingQuery);
		for (int i = 0; i < WARMUP; i++) {
			executionMs(query, lockingQuery);
		}
		double[] samples = new double[RUNS];
		for (int i = 0; i < RUNS; i++) {
			samples[i] = executionMs(query, lockingQuery);
		}
		Arrays.sort(samples);
		long size = indexName == null ? 0 : queryLong("SELECT pg_relation_size('" + indexName + "')");
		return new Variant(label, percentile(samples, 50), percentile(samples, 95), scanType(plan),
				resultRows(plan), size);
	}

	private static double executionMs(String query, boolean lockingQuery) throws Exception {
		for (String line : explain("EXPLAIN (ANALYZE, TIMING OFF) " + query, lockingQuery)) {
			if (line.trim().startsWith("Execution Time:")) {
				return Double.parseDouble(line.replaceAll("[^0-9.]", ""));
			}
		}
		throw new IllegalStateException("Execution Time 을 찾지 못함: " + query);
	}

	private static String explainPlan(String query, boolean lockingQuery) throws Exception {
		return String.join("\n", explain("EXPLAIN (ANALYZE, BUFFERS) " + query, lockingQuery));
	}

	private static List<String> explain(String sql, boolean lockingQuery) throws Exception {
		List<String> lines = new ArrayList<>();
		if (lockingQuery) {
			conn.setAutoCommit(false);
		}
		try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				lines.add(rs.getString(1));
			}
		} finally {
			if (lockingQuery) {
				conn.rollback();
				conn.setAutoCommit(true);
			}
		}
		return lines;
	}

	/** 최상위 노드의 실제 반환 행 수 */
	private static long resultRows(String plan) {
		Matcher m = Pattern.compile("actual time=[0-9.]+\\.\\.[0-9.]+ rows=(\\d+)").matcher(plan);
		return m.find() ? Long.parseLong(m.group(1)) : -1;
	}

	private static String scanType(String plan) {
		if (plan.contains("Index Only Scan")) return "Index Only Scan";
		if (plan.contains("Bitmap Index Scan")) return "Bitmap Index Scan";
		if (plan.contains("Index Scan")) return "Index Scan";
		if (plan.contains("Seq Scan")) return "Seq Scan";
		return "?";
	}

	private static double percentile(double[] sorted, int p) {
		int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
		return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
	}

	// ─────────────────────────────────────── report ────────────────────────────

	private static void section(String title) {
		System.out.println("\n══ " + title);
		report.append("\n## ").append(title).append('\n');
	}

	private static void table(int n, String caption, List<Variant> variants) {
		double baseline = variants.get(0).p50;
		StringBuilder t = new StringBuilder()
				.append("\n### ").append(String.format("%,d", n)).append("건")
				.append(caption == null ? "" : " — " + caption).append("\n\n")
				.append("| 인덱스 | 스캔 | 결과 행 | p50 (ms) | p95 (ms) | 없음 대비 | 인덱스 크기 |\n")
				.append("|---|---|---:|---:|---:|---:|---:|\n");
		for (Variant v : variants) {
			t.append(String.format("| %s | %s | %,d | %.3f | %.3f | %s | %s |%n",
					v.label, v.scan, v.rows, v.p50, v.p95,
					v.size == 0 ? "-" : String.format("%.1f배", baseline / v.p50),
					v.size == 0 ? "-" : humanSize(v.size)));
		}
		System.out.print(t);
		report.append(t);
	}

	private static String humanSize(long bytes) {
		if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
		return String.format("%.1f MB", bytes / (1024.0 * 1024));
	}

	// ─────────────────────────────────────── util ──────────────────────────────

	private static void vacuumAnalyze(String table) throws Exception {
		exec("VACUUM ANALYZE " + table);
	}

	private static void exec(String sql) throws Exception {
		try (Statement st = conn.createStatement()) {
			st.execute(sql);
		}
	}

	private static long queryLong(String sql) throws Exception {
		try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			if (!rs.next()) throw new IllegalStateException("결과 없음: " + sql);
			return rs.getLong(1);
		}
	}

	private static String queryString(String sql) throws Exception {
		try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			if (!rs.next()) throw new IllegalStateException("결과 없음: " + sql);
			return rs.getString(1);
		}
	}

	/** 인덱스 전략 하나의 측정 결과 */
	private record Variant(String label, double p50, double p95, String scan, long rows, long size) {}
}
