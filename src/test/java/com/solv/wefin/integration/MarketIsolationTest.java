package com.solv.wefin.integration;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import com.solv.wefin.common.HantuStubServer;
import com.solv.wefin.common.IntegrationTestBase;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.market.service.MarketService;
import com.solv.wefin.domain.trading.order.service.MarketOrderFacade;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * 한투 시세 API 장애 격리. 실제 HTTP 클라이언트가 로컬 스텁 서버를 호출한다.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MarketIsolationTest extends IntegrationTestBase {

	private static final HantuStubServer hantu = new HantuStubServer();

	@DynamicPropertySource
	static void hantuUrl(DynamicPropertyRegistry registry) {
		registry.add("hantu.api.baseUrl", hantu::baseUrl);
	}

	@Autowired
	private MarketOrderFacade facade;
	@Autowired
	private VirtualAccountService accountService;
	@Autowired
	private MarketService marketService;
	@Autowired
	private CircuitBreaker circuitBreaker;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private QuestProgressService questProgressService;

	private Long accountId;
	private List<Long> stockIds;

	@BeforeEach
	void setUp() {
		hantu.respondNormally();
		hantu.resetCalls();
		circuitBreaker.reset();
		clearPriceCache();
		UUID userId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO users (user_id, email, nickname, password, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'password', NOW(), NOW())", userId, "iso-" + userId + "@test.com",
			"iso-" + userId.toString().substring(0, 8));
		accountId = accountService.createAccount(userId).getVirtualAccountId();
		stockIds = jdbcTemplate.queryForList("SELECT stock_id FROM stock ORDER BY stock_id LIMIT 10", Long.class);
	}

	@AfterEach
	void cleanUp() {
		hantu.respondNormally();
		circuitBreaker.reset();
		jdbcTemplate.execute("DELETE FROM cash_ledger");
		jdbcTemplate.execute("DELETE FROM trade");
		jdbcTemplate.execute("DELETE FROM orders");
		jdbcTemplate.execute("DELETE FROM order_idempotency");
		jdbcTemplate.execute("DELETE FROM portfolio");
		jdbcTemplate.execute("DELETE FROM virtual_account");
		jdbcTemplate.execute("DELETE FROM users WHERE email LIKE 'iso-%'");
	}

	@Test
	void 한투가_실패를_거듭하면_회로가_열려_호출_없이_바로_거절하고_멱등키는_풀린다() {
		hantu.failPrice(500);
		for (int i = 0; i < 5; i++) {   // minimumNumberOfCalls = 5
			Long stockId = stockIds.get(i);
			assertMarketApiFailed(() -> facade.buyMarket(accountId, stockId, 1, UUID.randomUUID().toString()));
		}
		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
		int callsBefore = hantu.priceCalls();

		String key = UUID.randomUUID().toString();
		long start = System.nanoTime();
		assertMarketApiFailed(() -> facade.buyMarket(accountId, stockIds.get(5), 1, key));
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(hantu.priceCalls()).isEqualTo(callsBefore);   // 한투를 부르지 않았다
		assertThat(elapsedMs).isLessThan(500);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_idempotency WHERE idempotency_key = ?",
			Integer.class, key)).isZero();                        // 5xx 라 같은 키로 재시도 가능
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
	}

	@Test
	void 응답이_타임아웃보다_늦으면_기다리지_않고_2초_안에_거절한다() {
		hantu.delayPrice(5_000);

		long start = System.nanoTime();
		assertMarketApiFailed(() -> facade.buyMarket(accountId, stockIds.get(0), 1, UUID.randomUUID().toString()));
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(elapsedMs).isBetween(1_500L, 3_000L);
	}

	@Test
	void 한투가_회복되면_반열림_상태에서_시험_호출이_성공해_회로가_닫힌다() {
		circuitBreaker.transitionToOpenState();
		circuitBreaker.transitionToHalfOpenState();   // 열림 대기(10초)를 건너뛴다

		for (int i = 0; i < 3; i++) {                // permittedNumberOfCallsInHalfOpenState = 3
			facade.buyMarket(accountId, stockIds.get(i), 1, UUID.randomUUID().toString());
		}

		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	private static void assertMarketApiFailed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call)
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.MARKET_API_FAILED);
	}

	@SuppressWarnings("unchecked")
	private void clearPriceCache() {
		Object target = AopTestUtils.getTargetObject(marketService);
		for (String field : List.of("priceCache", "priceCacheTimestamp")) {
			((Map<String, ?>) ReflectionTestUtils.getField(target, field)).clear();
		}
	}
}
