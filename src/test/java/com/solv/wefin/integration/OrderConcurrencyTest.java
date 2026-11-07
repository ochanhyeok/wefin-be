package com.solv.wefin.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.solv.wefin.common.IntegrationTestBase;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.domain.trading.account.entity.VirtualAccount;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.market.client.HantuMarketClient;
import com.solv.wefin.domain.trading.market.client.HantuTokenManager;
import com.solv.wefin.domain.trading.market.client.dto.HantuPriceApiResponse;
import com.solv.wefin.domain.trading.order.service.OrderService;
import org.springframework.data.redis.core.StringRedisTemplate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class OrderConcurrencyTest extends IntegrationTestBase {

	@Autowired
	private OrderService orderService;
	@Autowired
	private VirtualAccountService accountService;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private HantuMarketClient hantuMarketClient;
	@MockitoBean
	private HantuTokenManager hantuTokenManager;
	@MockitoBean
	private QuestProgressService questProgressService;

	private UUID userId;
	private Long stockId;
	private Long accountId;
	private String email;

	@BeforeEach
	void setUp() {
		HantuPriceApiResponse.Output mockOutput = mock(HantuPriceApiResponse.Output.class);
		given(mockOutput.stck_prpr()).willReturn("1500000");
		given(mockOutput.prdy_vrss()).willReturn("0");
		given(mockOutput.prdy_ctrt()).willReturn("0.0");
		given(mockOutput.acml_vol()).willReturn("0");
		given(mockOutput.stck_oprc()).willReturn("186000");
		given(mockOutput.stck_hgpr()).willReturn("186000");
		given(mockOutput.stck_lwpr()).willReturn("186000");
		HantuPriceApiResponse mockResponse = new HantuPriceApiResponse(mockOutput);
		given(hantuMarketClient.fetchCurrentPrice(anyString())).willReturn(mockResponse);

		userId = UUID.randomUUID();
		email = "test-" + userId + "@test.com";

		jdbcTemplate.update(
				"INSERT INTO users (user_id, email, nickname, password, created_at, updated_at) " +
						"VALUES (?, ?, 'tester', 'password', NOW(), NOW())",
				userId, email
		);

		stockId = jdbcTemplate.queryForObject(
			"SELECT stock_id FROM stock WHERE stock_code = '005930'", Long.class
		);

		VirtualAccount account = accountService.createAccount(userId);
		accountId = account.getVirtualAccountId();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.execute("DELETE FROM trade");
		jdbcTemplate.execute("DELETE FROM orders");
		jdbcTemplate.execute("DELETE FROM order_idempotency");
		jdbcTemplate.execute("DELETE FROM portfolio");
		jdbcTemplate.execute("DELETE FROM virtual_account");
		jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
		var keys = redisTemplate.keys("idempotency:order:*");
		if (keys != null && !keys.isEmpty()) {
			redisTemplate.delete(keys);
		}
	}

	@Test
	void 동시_매수_10건_잔고_음수_안됨() throws Exception{
		// given - setUp()에서 계좌 (1000만원) + 종목 준비
		int threadCount = 10;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);
		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failCount = new AtomicInteger(0);

		// when - 10개 스레드에서 동시에 매수
		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					orderService.buyMarket(accountId, stockId, 1); // 1주 매수
					successCount.incrementAndGet();
				} catch (Exception e) {
					log.error("매수 실패: {}", e.getMessage());
					failCount.incrementAndGet();
				} finally {
					latch.countDown();
				}
			});
		}

		// 전부 끝날 때까지 대기
		latch.await(10, TimeUnit.SECONDS);
		executor.shutdown();

		// then - 검증
		VirtualAccount result = accountService.getAccountByUserId(userId);
		assertThat(result.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
		assertThat(successCount.get()).isLessThanOrEqualTo(6);

		log.info("성공: {}, 실패: {}, 잔고: {}", successCount.get(), failCount.get(), result.getBalance());
	}

	@Test
	void 동시_매수5건_매도5건_잔고_정합성_유지() throws Exception {
		// given - 5주 사전 매수
		for (int i = 0; i < 5; i++) {
			orderService.buyMarket(accountId, stockId, 1);
		}

		VirtualAccount before = accountService.getAccountByUserId(userId);
		BigDecimal startBalance = before.getBalance();

		int startQuantity = 5;

		int totalThread = 10;
		ExecutorService executor = Executors.newFixedThreadPool(totalThread);
		CountDownLatch latch = new CountDownLatch(totalThread);

		AtomicInteger buySuccess = new AtomicInteger(0);
		AtomicInteger sellSuccess = new AtomicInteger(0);
		AtomicInteger buyFail = new AtomicInteger(0);
		AtomicInteger sellFail = new AtomicInteger(0);

		// when
		for (int i = 0; i < 5; i++) {
			executor.submit(() -> {
				try {
					orderService.buyMarket(accountId, stockId, 1);
					buySuccess.incrementAndGet();
				} catch (Exception e) {
					buyFail.incrementAndGet();
				} finally {
					latch.countDown();
				}
			});
		}

		for (int i = 0; i < 5; i++) {
			executor.submit(() -> {
				try {
					orderService.sellMarket(accountId, stockId, 1);
					sellSuccess.incrementAndGet();
				} catch (Exception e) {
					sellFail.incrementAndGet();
				} finally {
					latch.countDown();
				}
			});
		}

		latch.await(10, TimeUnit.SECONDS);
		executor.shutdown();

		// then
		// 최종 계좌 조회
		VirtualAccount result = accountService.getAccountByUserId(userId);

		// 매수/매도 단가 계산
		BigDecimal price = new BigDecimal("1500000");
		BigDecimal fee = price.multiply(new BigDecimal("0.00015")).setScale(0, RoundingMode.DOWN);
		BigDecimal tax = price.multiply(new BigDecimal("0.0018")).setScale(0, RoundingMode.DOWN);
		BigDecimal buyCost = price.add(fee);
		BigDecimal sellRevenue = price.subtract(fee).subtract(tax);

		// 기대잔고 계산
		BigDecimal expectedBalance = startBalance.subtract(buyCost.multiply(BigDecimal.valueOf(buySuccess.get())))
			.add(sellRevenue.multiply(BigDecimal.valueOf(sellSuccess.get())));

		// 검증
		assertThat(result.getBalance()).isEqualByComparingTo(expectedBalance);
		assertThat(result.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

		Integer finalQuantity;
		try {
			finalQuantity = jdbcTemplate.queryForObject(
				"SELECT quantity FROM portfolio WHERE virtual_account_id = ? AND stock_id = ?",
				Integer.class, accountId, stockId
			);
		} catch (EmptyResultDataAccessException e) {
			finalQuantity = 0;
			log.info("포트폴리오 행 없음 - 전량 매도 완료로 판단, 수량 0 처리");
		}

		int expectedQuantity = startQuantity + buySuccess.get() - sellSuccess.get();
		assertThat(finalQuantity).isEqualTo(expectedQuantity);

		log.info("매수 성공: {}, 매수 실패:{}, 매도 성공: {}, 매도 실패:{}, 최종 잔고: {}",
			buySuccess.get(), buyFail.get(), sellSuccess.get(), sellFail.get(), result.getBalance());
	}

	@Test
	void 동시_매도_10건_보유수량_초과_안됨() throws Exception {
		// given - 5주 사전 매수
		for (int i = 0; i < 5; i++) {
			orderService.buyMarket(accountId, stockId, 1);
		}

		int threadCount = 10;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(threadCount);
		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failCount = new AtomicInteger(0);

		// when
		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					orderService.sellMarket(accountId, stockId, 1);
					successCount.incrementAndGet();
				} catch (Exception e) {
					log.info("매도 실패: {}", e.getMessage());
					failCount.incrementAndGet();
				} finally {
					latch.countDown();
				}
			});
		}

		latch.await(10, TimeUnit.SECONDS);
		executor.shutdown();

		// then
		VirtualAccount result = accountService.getAccountByUserId(userId);
		Integer finalQuantity;
		try {
			finalQuantity = jdbcTemplate.queryForObject(
				"SELECT quantity FROM portfolio WHERE virtual_account_id = ? AND stock_id = ?",
				Integer.class, accountId, stockId
			);
		} catch (EmptyResultDataAccessException e) {
			finalQuantity = 0;
			log.info("포트폴리오 행 없음 - 전량 매도 완료로 판단, 수량 0 처리");
		}

		assertThat(finalQuantity).isGreaterThanOrEqualTo(0);
		log.info("성공: {}, 실패: {}, 잔고: {}", successCount, failCount, result.getBalance());
	}

	@Test
	void 멱등키_다르면_동시_요청은_각각_별개_주문으로_생성된다() throws Exception {
		// given - 멱등키 없이(=요청마다 새 키) 동시 5건. 서로 다른 의도로 간주되어 모두 정상 주문이어야 한다.
		int requests = 5;
		ExecutorService executor = Executors.newFixedThreadPool(requests);
		CountDownLatch ready = new CountDownLatch(requests);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(requests);
		AtomicInteger success = new AtomicInteger(0);
		AtomicInteger rejected = new AtomicInteger(0);

		// when - 5개 스레드가 같은 출발선에서 동시에 매수 (각자 다른 멱등키 = 별개 의도)
		for (int i = 0; i < requests; i++) {
			executor.submit(() -> {
				try {
					ready.countDown();
					start.await();
					orderService.buyMarket(accountId, stockId, 1);   // 3-arg = 내부에서 새 키 발급
					success.incrementAndGet();
				} catch (Exception e) {
					rejected.incrementAndGet();
					log.info("거부됨: {}", e.getMessage());
				} finally {
					done.countDown();
				}
			});
		}
		ready.await();
		start.countDown();              // 동시에 출발
		done.await(10, TimeUnit.SECONDS);
		executor.shutdown();

		// then - 키가 다르므로 5건 모두 별개 주문으로 생성됨 (정상 빠른 재주문)
		Integer orderCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM orders WHERE virtual_account_id = ?", Integer.class, accountId);
		log.info("[멱등키 다름] 동시 {}건 → 성공 {}건 / 거부 {}건 / 생성된 주문 {}건",
				requests, success.get(), rejected.get(), orderCount);
		assertThat(orderCount).isEqualTo(requests);   // 다른 의도 → 5건 다 생성
	}

	@Test
	void 멱등키_같으면_동시_중복요청도_주문_1건만_생성된다() throws Exception {
		// given - 같은 의도(따닥): 동일 멱등키를 5개 동시 요청이 공유한다 (더블클릭/네트워크 재시도)
		int duplicateRequests = 5;
		String sameKey = "TADAK-" + UUID.randomUUID();
		ExecutorService executor = Executors.newFixedThreadPool(duplicateRequests);
		CountDownLatch ready = new CountDownLatch(duplicateRequests);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(duplicateRequests);
		AtomicInteger success = new AtomicInteger(0);
		AtomicInteger rejected = new AtomicInteger(0);

		// when - 5개 스레드가 같은 멱등키로 동시에 매수 (진짜 따닥)
		for (int i = 0; i < duplicateRequests; i++) {
			executor.submit(() -> {
				try {
					ready.countDown();
					start.await();
					orderService.buyMarket(accountId, stockId, 1, sameKey);   // 전부 같은 키
					success.incrementAndGet();
				} catch (Exception e) {
					rejected.incrementAndGet();
					log.info("멱등 차단: {}", e.getMessage());
				} finally {
					done.countDown();
				}
			});
		}
		ready.await();
		start.countDown();
		done.await(10, TimeUnit.SECONDS);
		executor.shutdown();

		// then - 멱등키 선점으로 1건만 성공, 나머지는 차단 → 주문 1건
		Integer orderCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM orders WHERE virtual_account_id = ?", Integer.class, accountId);
		log.info("[멱등키 같음] 같은 키 동시 {}건 → 성공 {}건 / 거부 {}건 / 생성된 주문 {}건",
				duplicateRequests, success.get(), rejected.get(), orderCount);
		assertThat(orderCount).isEqualTo(1);                       // 따닥 차단
		assertThat(success.get()).isEqualTo(1);
		assertThat(rejected.get()).isEqualTo(duplicateRequests - 1);
	}
}
