package com.solv.wefin.benchmark;

import static org.mockito.BDDMockito.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.solv.wefin.common.IntegrationTestBase;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.market.service.MarketService;
import com.solv.wefin.domain.trading.order.service.MarketOrderFacade;
import com.solv.wefin.common.HantuStubServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 외부 시세 API(한투) 장애가 주문과 무관한 DB 작업까지 번지는지 측정한다.
 *
 * <p>한투를 흉내 내는 로컬 HTTP 서버가 시세 응답을 일부러 늦춘다. 캐시에 없는 20개 종목을 동시에 시장가 매수하는 동안,
 * 주문과 무관한 계좌 조회(읽기 트랜잭션)를 반복해 응답 시간과 커넥션 풀 점유를 기록한다.</p>
 *
 * 실행: {@code ./gradlew benchmark --tests '*MarketOutageBenchmarkTest'}
 */
@Tag("benchmark")
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MarketOutageBenchmarkTest extends IntegrationTestBase {

	private static final int BUYERS = 20;
	private static final int PROBES = 20;
	private static final Path REPORT = Path.of("build/benchmark-results/market-outage.md");

	private static final HantuStubServer hantu = new HantuStubServer();

	@DynamicPropertySource
	static void hantuUrl(DynamicPropertyRegistry registry) {
		registry.add("hantu.api.baseUrl", hantu::baseUrl);
	}

	@Autowired
	private MarketOrderFacade marketOrderFacade;
	@Autowired
	private VirtualAccountService accountService;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private DataSource dataSource;
	@Autowired
	private MarketService marketService;
	@Autowired
	private CircuitBreaker circuitBreaker;

	@MockitoBean
	private QuestProgressService questProgressService;

	private static final List<String> report = Collections.synchronizedList(new ArrayList<>());

	@AfterEach
	void cleanUp() {
		jdbcTemplate.execute("DELETE FROM cash_ledger");
		jdbcTemplate.execute("DELETE FROM trade");
		jdbcTemplate.execute("DELETE FROM orders");
		jdbcTemplate.execute("DELETE FROM order_idempotency");
		jdbcTemplate.execute("DELETE FROM portfolio");
		jdbcTemplate.execute("DELETE FROM virtual_account");
		jdbcTemplate.execute("DELETE FROM users WHERE email LIKE 'outage-%'");
	}

	@AfterAll
	static void writeReport() throws IOException {
		hantu.stop();
		Files.createDirectories(REPORT.getParent());
		String label = System.getenv().getOrDefault("OUTAGE_LABEL", "current");
		StringBuilder md = new StringBuilder("## 시세 API 장애 격리 측정 (" + label + ")\n\n")
			.append("매수 ").append(BUYERS).append("건(캐시 없는 종목 각 1건) 동시 + 주문과 무관한 계좌 조회 ")
			.append(PROBES).append("회. 커넥션 풀 최대 10\n\n")
			.append("2차 매수: 1차가 끝난 직후 같은 20건을 한 번 더 보냈을 때 (서킷 브레이커가 열렸는지 보는 용도)\n\n")
			.append("| 시나리오 | 1차 매수 성공/실패 | 1차 매수 p50 / 최대 (ms) | 무관한 조회 성공/실패 | 무관한 조회 p50 / 최대 (ms, 실패 포함) | 풀 사용 최대 / 대기 스레드 최대 | 2차 매수 성공/실패 | 2차 매수 p50 / 최대 (ms) | 한투 호출 (1차+2차) |\n")
			.append("|---|---|---|---|---|---|---|---|---|\n");
		report.forEach(md::append);
		Files.writeString(REPORT, md.toString(), StandardCharsets.UTF_8,
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		System.out.println(md);
	}

	@Test
	void 정상_응답() throws Exception {
		measure("정상", 0);
	}

	@Test
	void 응답_지연_1초() throws Exception {
		measure("응답 지연 1초", 1_000);
	}

	@Test
	void 응답_지연_3초() throws Exception {
		measure("응답 지연 3초", 3_000);
	}

	@Test
	void 무응답() throws Exception {
		measure("무응답 (30초)", 30_000);
	}

	private void measure(String scenario, long delayMs) throws Exception {
		hantu.respondNormally();
		hantu.delayPrice(delayMs);
		hantu.resetCalls();
		circuitBreaker.reset();
		List<String> stockCodes = jdbcTemplate.queryForList(
			"SELECT stock_code FROM stock ORDER BY stock_id LIMIT ?", String.class, BUYERS);
		List<Long> stockIds = jdbcTemplate.queryForList(
			"SELECT stock_id FROM stock ORDER BY stock_id LIMIT ?", Long.class, BUYERS);
		List<Long> accounts = new ArrayList<>();
		List<UUID> users = new ArrayList<>();
		for (int i = 0; i < BUYERS; i++) {
			UUID userId = UUID.randomUUID();
			jdbcTemplate.update("INSERT INTO users (user_id, email, nickname, password, created_at, updated_at) "
					+ "VALUES (?, ?, ?, 'password', NOW(), NOW())", userId, "outage-" + userId + "@test.com",
				"outage-" + userId.toString().substring(0, 8));
			users.add(userId);
			accounts.add(accountService.createAccount(userId).getVirtualAccountId());
		}
		clearPriceCache(stockCodes);

		HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
		AtomicInteger peakActive = new AtomicInteger();
		AtomicInteger peakWaiting = new AtomicInteger();
		AtomicBoolean sampling = new AtomicBoolean(true);
		Thread sampler = new Thread(() -> {
			while (sampling.get()) {
				peakActive.accumulateAndGet(hikari.getHikariPoolMXBean().getActiveConnections(), Math::max);
				peakWaiting.accumulateAndGet(hikari.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max);
				try { Thread.sleep(20); } catch (InterruptedException e) { return; }
			}
		});
		sampler.start();

		ExecutorService pool = Executors.newFixedThreadPool(BUYERS);
		List<Long> buyLatencies = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger ok = new AtomicInteger();
		AtomicInteger failed = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(BUYERS);
		for (int i = 0; i < BUYERS; i++) {
			Long account = accounts.get(i);
			Long stockId = stockIds.get(i);
			pool.submit(() -> {
				try {
					start.await();
					long t = System.nanoTime();
					try {
						marketOrderFacade.buyMarket(account, stockId, 1, UUID.randomUUID().toString());
						ok.incrementAndGet();
					} catch (RuntimeException e) {
						failed.incrementAndGet();
					} finally {
						buyLatencies.add((System.nanoTime() - t) / 1_000_000);
					}
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		Thread.sleep(300);   // 매수들이 시세를 기다리기 시작한 뒤

		List<Long> probeLatencies = new ArrayList<>();
		int probeFailed = 0;
		for (int i = 0; i < PROBES; i++) {
			long t = System.nanoTime();
			try {
				accountService.getAccountByUserId(users.get(i % users.size()));
			} catch (RuntimeException e) {
				probeFailed++;   // 커넥션 획득 실패도 지연으로 기록하되, 빠른 실패를 정상 응답으로 오인하지 않게 따로 센다
			}
			probeLatencies.add((System.nanoTime() - t) / 1_000_000);
			Thread.sleep(100);
		}
		done.await(120, TimeUnit.SECONDS);
		pool.shutdown();
		sampling.set(false);
		sampler.join();

		// 2차: 같은 조건으로 한 번 더. 회로가 열렸다면 한투를 부르지 않고 바로 실패한다
		clearPriceCache(stockCodes);
		ExecutorService pool2 = Executors.newFixedThreadPool(BUYERS);
		List<Long> wave2 = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger ok2 = new AtomicInteger();
		AtomicInteger failed2 = new AtomicInteger();
		CountDownLatch done2 = new CountDownLatch(BUYERS);
		for (int i = 0; i < BUYERS; i++) {
			Long account = accounts.get(i);
			Long stockId = stockIds.get(i);
			pool2.submit(() -> {
				long t = System.nanoTime();
				try {
					marketOrderFacade.buyMarket(account, stockId, 1, UUID.randomUUID().toString());
					ok2.incrementAndGet();
				} catch (RuntimeException e) {
					failed2.incrementAndGet();
				} finally {
					wave2.add((System.nanoTime() - t) / 1_000_000);
					done2.countDown();
				}
			});
		}
		done2.await(120, TimeUnit.SECONDS);
		pool2.shutdown();

		report.add(String.format("| %s | %d / %d | %d / %d | %d / %d | %d / %d | %d / %d | %d / %d | %d / %d | %d |%n", scenario,
			ok.get(), failed.get(), p50(buyLatencies), max(buyLatencies), PROBES - probeFailed, probeFailed,
			p50(probeLatencies), max(probeLatencies),
			peakActive.get(), peakWaiting.get(), ok2.get(), failed2.get(), p50(wave2), max(wave2), hantu.priceCalls()));
	}

	/** 다른 테스트 메서드가 채운 시세 캐시를 비워 매번 한투를 호출하게 한다 */
	@SuppressWarnings("unchecked")
	private void clearPriceCache(List<String> stockCodes) {
		Object target = AopTestUtils.getTargetObject(marketService);
		for (String field : List.of("priceCache", "priceCacheTimestamp")) {
			Map<String, ?> cache = (Map<String, ?>) ReflectionTestUtils.getField(target, field);
			stockCodes.forEach(cache::remove);
		}
	}

	private static long p50(List<Long> values) {
		List<Long> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
	}

	private static long max(List<Long> values) {
		return values.stream().mapToLong(Long::longValue).max().orElse(0);
	}
}
