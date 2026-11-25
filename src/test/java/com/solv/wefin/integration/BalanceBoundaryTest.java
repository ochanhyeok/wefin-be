package com.solv.wefin.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.solv.wefin.common.IntegrationTestBase;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.common.TradingConstants;
import com.solv.wefin.domain.trading.market.service.MarketService;
import com.solv.wefin.domain.trading.order.service.MarketOrderFacade;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

/**
 * 잔고 경계값. 시장가 매수가 실제 DB의 조건부 UPDATE(balance >= amount)를 거쳐
 * 잔고와 정확히 같은 금액은 체결하고, 1원 부족하면 잔고·주문·원장을 건드리지 않고 거절하는지 확인한다.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BalanceBoundaryTest extends IntegrationTestBase {

	private static final String STOCK_CODE = "005930";
	private static final BigDecimal PRICE = new BigDecimal("100000");
	/** 1주 매수에 필요한 금액 (체결 금액 + 수수료) */
	private static final BigDecimal COST = PRICE.add(PRICE.multiply(TradingConstants.FEE_RATE).setScale(0, RoundingMode.DOWN));

	@Autowired
	private MarketOrderFacade marketOrderFacade;
	@Autowired
	private VirtualAccountService accountService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private MarketService marketPriceProvider;   // MarketPriceProvider 구현체. 구체 타입으로 주입받는 곳이 있어 구현체째 대체
	@MockitoBean
	private QuestProgressService questProgressService;

	private Long stockId;

	@BeforeEach
	void setUp() {
		given(marketPriceProvider.getCurrentPrice(anyString())).willReturn(PRICE);
		stockId = jdbcTemplate.queryForObject("SELECT stock_id FROM stock WHERE stock_code = ?", Long.class, STOCK_CODE);
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.execute("DELETE FROM cash_ledger");
		jdbcTemplate.execute("DELETE FROM trade");
		jdbcTemplate.execute("DELETE FROM orders");
		jdbcTemplate.execute("DELETE FROM order_idempotency");
		jdbcTemplate.execute("DELETE FROM portfolio");
		jdbcTemplate.execute("DELETE FROM virtual_account");
		jdbcTemplate.execute("DELETE FROM users WHERE email LIKE 'boundary-%'");
	}

	@Test
	void 잔고와_정확히_같은_금액이면_체결하고_잔고는_0이_된다() {
		Long account = createAccountWithBalance(COST);

		marketOrderFacade.buyMarket(account, stockId, 1, UUID.randomUUID().toString());

		assertThat(balance(account)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(orderCount(account)).isEqualTo(1);
		assertThat(buyLedgerSum(account)).isEqualByComparingTo(COST.negate());
	}

	@Test
	void 일원이_부족하면_거절하고_잔고_주문_원장을_건드리지_않는다() {
		BigDecimal oneShort = COST.subtract(BigDecimal.ONE);
		Long account = createAccountWithBalance(oneShort);

		assertInsufficient(account);

		assertThat(balance(account)).isEqualByComparingTo(oneShort);
		assertThat(orderCount(account)).isZero();
		assertThat(buyLedgerSum(account)).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void 잔고가_0이면_거절한다() {
		Long account = createAccountWithBalance(BigDecimal.ZERO);

		assertInsufficient(account);

		assertThat(balance(account)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(orderCount(account)).isZero();
	}

	@Test
	void 나누어떨어지지_않는_잔고에_동시_매수가_몰려도_살_수_있는_만큼만_체결하고_남은_잔고는_보존한다() throws Exception {
		int affordable = 3;
		int requests = 10;
		BigDecimal remainder = COST.subtract(BigDecimal.ONE);
		Long account = createAccountWithBalance(COST.multiply(BigDecimal.valueOf(affordable)).add(remainder));

		ExecutorService pool = Executors.newFixedThreadPool(requests);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(requests);
		AtomicInteger success = new AtomicInteger();
		AtomicInteger insufficient = new AtomicInteger();
		Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
		try {
			for (int i = 0; i < requests; i++) {
				pool.submit(() -> {
					try {
						start.await();
						marketOrderFacade.buyMarket(account, stockId, 1, UUID.randomUUID().toString());
						success.incrementAndGet();
					} catch (BusinessException e) {
						if (e.getErrorCode() == ErrorCode.ORDER_INSUFFICIENT_BALANCE) {
							insufficient.incrementAndGet();
						} else {
							unexpected.add(e);
						}
					} catch (Throwable e) {
						unexpected.add(e);
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdownNow();
		}

		assertThat(unexpected).isEmpty();
		assertThat(success.get()).isEqualTo(affordable);
		assertThat(insufficient.get()).isEqualTo(requests - affordable);
		assertThat(balance(account)).isEqualByComparingTo(remainder);
		assertThat(orderCount(account)).isEqualTo(affordable);
		assertThat(holding(account)).isEqualTo(affordable);
	}

	private void assertInsufficient(Long account) {
		assertThatThrownBy(() -> marketOrderFacade.buyMarket(account, stockId, 1, UUID.randomUUID().toString()))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.ORDER_INSUFFICIENT_BALANCE);
	}

	/** 계좌를 만들고 잔고를 원하는 값으로 맞춘다. 원장과의 대사는 이 테스트의 관심사가 아니다 */
	private Long createAccountWithBalance(BigDecimal balance) {
		UUID userId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO users (user_id, email, nickname, password, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'password', NOW(), NOW())", userId, "boundary-" + userId + "@test.com",
			"boundary-" + userId.toString().substring(0, 8));
		Long id = accountService.createAccount(userId).getVirtualAccountId();
		jdbcTemplate.update("UPDATE virtual_account SET balance = ? WHERE virtual_account_id = ?", balance, id);
		return id;
	}

	private BigDecimal balance(Long account) {
		return jdbcTemplate.queryForObject(
			"SELECT balance FROM virtual_account WHERE virtual_account_id = ?", BigDecimal.class, account);
	}

	private int orderCount(Long account) {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM orders WHERE virtual_account_id = ?", Integer.class, account);
	}

	private BigDecimal buyLedgerSum(Long account) {
		return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM cash_ledger "
			+ "WHERE virtual_account_id = ? AND entry_type = 'MARKET_BUY'", BigDecimal.class, account);
	}

	private int holding(Long account) {
		return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM portfolio "
			+ "WHERE virtual_account_id = ?", Integer.class, account);
	}
}
