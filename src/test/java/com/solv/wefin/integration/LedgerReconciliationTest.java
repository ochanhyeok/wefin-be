package com.solv.wefin.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.solv.wefin.common.IntegrationTestBase;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.ledger.entity.CashLedgerType;
import com.solv.wefin.domain.trading.ledger.service.CashLedgerService;
import com.solv.wefin.domain.trading.market.service.MarketService;
import com.solv.wefin.domain.trading.matching.service.LimitOrderMatchingService;
import com.solv.wefin.domain.trading.order.dto.OrderInfo;
import com.solv.wefin.domain.trading.order.service.LimitOrderService;
import com.solv.wefin.domain.trading.order.service.MarketOrderFacade;
import com.solv.wefin.domain.trading.order.service.OrderService;
import com.solv.wefin.domain.trading.reconciliation.entity.ReconciliationRun;
import com.solv.wefin.domain.trading.reconciliation.repository.ReconciliationQueryRepository;
import com.solv.wefin.domain.trading.reconciliation.service.ReconciliationService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

/**
 * 현금 원장과 대사. "잔고가 맞다"를 테스트 통과가 아니라 원장으로 다시 계산해 대조할 수 있는지 검증한다.
 *
 * <p>DB 에는 다른 테스트 클래스가 남긴 계좌가 있을 수 있어, 불일치는 이 테스트가 만든 계좌로 한정해 확인한다.</p>
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerReconciliationTest extends IntegrationTestBase {

	private static final String STOCK_CODE = "005930";
	private static final BigDecimal PRICE = new BigDecimal("100000");

	@Autowired
	private OrderService orderService;
	@Autowired
	private MarketOrderFacade marketOrderFacade;
	@Autowired
	private LimitOrderService limitOrderService;
	@Autowired
	private LimitOrderMatchingService matchingService;
	@Autowired
	private VirtualAccountService accountService;
	@Autowired
	private CashLedgerService cashLedgerService;
	@Autowired
	private ReconciliationService reconciliationService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private ReconciliationQueryRepository queryRepository;
	@MockitoBean
	private MarketService marketPriceProvider;   // MarketPriceProvider 구현체. 구체 타입으로 주입받는 곳이 있어 구현체째 대체
	@MockitoBean
	private QuestProgressService questProgressService;

	private Long stockId;
	private final List<Long> accounts = new ArrayList<>();

	@BeforeEach
	void setUp() {
		given(marketPriceProvider.getCurrentPrice(anyString())).willReturn(PRICE);
		stockId = jdbcTemplate.queryForObject("SELECT stock_id FROM stock WHERE stock_code = ?", Long.class, STOCK_CODE);
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.execute("DELETE FROM reconciliation_mismatch");
		jdbcTemplate.execute("DELETE FROM reconciliation_run");
		jdbcTemplate.execute("DELETE FROM cash_ledger");
		jdbcTemplate.execute("DELETE FROM trade");
		jdbcTemplate.execute("DELETE FROM orders");
		jdbcTemplate.execute("DELETE FROM portfolio");
		jdbcTemplate.execute("DELETE FROM virtual_account");
		jdbcTemplate.execute("DELETE FROM users WHERE email LIKE 'ledger-%'");
		accounts.clear();
	}

	@Test
	void 매수_매도_지정가_예약_정정_체결_취소를_모두_거쳐도_대사가_일치한다() {
		Long account = createAccount();

		marketOrderFacade.buyMarket(account, stockId, 5, UUID.randomUUID().toString());                                     // 시장가 매수
		marketOrderFacade.sellMarket(account, stockId, 1);                                    // 시장가 매도
		OrderInfo limitBuy = limitOrderService.buyLimit(account, stockId, 2, new BigDecimal("120000"));
		orderService.modifyOrder(account, limitBuy.order().getOrderNo(), new BigDecimal("110000"), 3);   // 정정: 예약금 추가
		OrderInfo toCancel = limitOrderService.buyLimit(account, stockId, 1, new BigDecimal("90000"));
		orderService.cancelOrder(account, toCancel.order().getOrderNo());                // 취소: 예약금 환불
		limitOrderService.sellLimit(account, stockId, 1, new BigDecimal("100000"));
		matchingService.matchLimitOrders(STOCK_CODE, new BigDecimal("105000"));          // 매수는 싸게 체결(차액 환불), 매도 체결
		accountService.depositBalance(account, new BigDecimal("3000"), CashLedgerType.QUEST_REWARD);

		ReconciliationRun run = reconciliationService.run(ReconciliationRun.Trigger.MANUAL);

		assertThat(run.getStatus()).isEqualTo(ReconciliationRun.Status.SUCCEEDED);
		assertThat(mismatchesOf(run, account)).isEmpty();
		assertThat(balance(account)).isEqualByComparingTo(ledgerSum(account));
		assertThat(ledgerTypes(account)).contains("ACCOUNT_OPEN", "MARKET_BUY", "MARKET_SELL", "LIMIT_BUY_RESERVE",
			"ORDER_MODIFY_ADJUST", "ORDER_CANCEL_REFUND", "LIMIT_BUY_FILL_REFUND", "LIMIT_SELL_FILL", "QUEST_REWARD");
	}

	@Test
	void 원장을_거치지_않고_잔고를_바꾸면_계좌_불일치로_잡고_자동으로_고치지_않는다() {
		Long account = createAccount();
		marketOrderFacade.buyMarket(account, stockId, 1, UUID.randomUUID().toString());
		jdbcTemplate.update("UPDATE virtual_account SET balance = balance + 1000 WHERE virtual_account_id = ?", account);
		BigDecimal tampered = balance(account);

		ReconciliationRun run = reconciliationService.run(ReconciliationRun.Trigger.MANUAL);

		List<Map<String, Object>> mismatches = mismatchesOf(run, account);
		assertThat(mismatches).hasSize(1);
		assertThat(mismatches.get(0).get("mismatch_type")).isEqualTo("ACCOUNT");
		assertThat((BigDecimal) mismatches.get(0).get("actual"))
			.isEqualByComparingTo(((BigDecimal) mismatches.get(0).get("expected")).add(new BigDecimal("1000")));
		assertThat(balance(account)).isEqualByComparingTo(tampered);   // 대사는 기록만 한다
	}

	@Test
	void 원장_기록이_빠진_주문은_주문_대사에서_주문번호까지_잡힌다() {
		Long account = createAccount();
		OrderInfo order = limitOrderService.buyLimit(account, stockId, 1, new BigDecimal("90000"));
		orderService.cancelOrder(account, order.order().getOrderNo());
		jdbcTemplate.update("DELETE FROM cash_ledger WHERE order_id = ? AND entry_type = 'ORDER_CANCEL_REFUND'",
			order.order().getOrderId());

		ReconciliationRun run = reconciliationService.run(ReconciliationRun.Trigger.MANUAL);

		assertThat(mismatchesOf(run, account))
			.extracting(m -> m.get("mismatch_type"), m -> m.get("order_id"))
			.containsExactlyInAnyOrder(
				tuple("ACCOUNT", null),                       // 잔고는 환불됐는데 원장엔 없다
				tuple("ORDER", order.order().getOrderId()));  // 취소된 주문의 현금 이동이 0 이 아니다
	}

	@Test
	void 주문이_들어오는_중에_대사해도_중간_상태를_불일치로_보지_않는다() throws Exception {
		for (int i = 0; i < 4; i++) {
			createAccount();
		}
		ExecutorService pool = Executors.newFixedThreadPool(accounts.size());
		AtomicBoolean running = new AtomicBoolean(true);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		CountDownLatch done = new CountDownLatch(accounts.size());
		for (Long account : new ArrayList<>(accounts)) {
			pool.submit(() -> {
				try {
					while (running.get()) {
						marketOrderFacade.buyMarket(account, stockId, 1, UUID.randomUUID().toString());
					}
				} catch (BusinessException e) {
					// 잔고 소진(ORDER_INSUFFICIENT_BALANCE)으로 끝나는 건 정상
					if (e.getErrorCode() != ErrorCode.ORDER_INSUFFICIENT_BALANCE) {
						errors.add(e);
					}
				} catch (Throwable e) {
					errors.add(e);
				} finally {
					done.countDown();
				}
			});
		}

		List<ReconciliationRun> runs = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			runs.add(reconciliationService.run(ReconciliationRun.Trigger.MANUAL));
		}
		running.set(false);
		assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		pool.shutdown();

		assertThat(errors).isEmpty();
		assertThat(orderCount()).isGreaterThan(0);
		for (ReconciliationRun run : runs) {
			for (Long account : accounts) {
				assertThat(mismatchesOf(run, account)).isEmpty();
			}
		}
	}

	@Test
	void 이미_실행_중이면_두번째_실행은_거절된다() {
		jdbcTemplate.update("INSERT INTO reconciliation_run (status, trigger_type, started_at) VALUES ('RUNNING', 'SCHEDULED', now())");

		assertThatThrownBy(() -> reconciliationService.run(ReconciliationRun.Trigger.MANUAL))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.RECONCILIATION_ALREADY_RUNNING);
	}

	@Test
	void 실행하던_서버가_죽어_30분_넘게_RUNNING이면_정리하고_새로_실행한다() {
		jdbcTemplate.update("INSERT INTO reconciliation_run (status, trigger_type, started_at) "
			+ "VALUES ('RUNNING', 'SCHEDULED', now() - interval '31 minutes')");

		ReconciliationRun run = reconciliationService.run(ReconciliationRun.Trigger.MANUAL);

		assertThat(run.getStatus()).isEqualTo(ReconciliationRun.Status.SUCCEEDED);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM reconciliation_run WHERE status = 'FAILED' AND error_message LIKE '%중단%'",
			Integer.class)).isEqualTo(1);
	}

	@Test
	void 검사_중_예외가_나면_FAILED와_원인을_남기고_다음_실행을_막지_않는다() {
		willThrow(new IllegalStateException("주문 대사 쿼리 실패")).given(queryRepository).findOrderMismatches();

		assertThatThrownBy(() -> reconciliationService.run(ReconciliationRun.Trigger.SCHEDULED))
			.isInstanceOf(IllegalStateException.class);

		Map<String, Object> failed = jdbcTemplate.queryForMap(
			"SELECT status, error_message FROM reconciliation_run ORDER BY run_id DESC LIMIT 1");
		assertThat(failed.get("status")).isEqualTo("FAILED");
		assertThat((String) failed.get("error_message")).contains("주문 대사 쿼리 실패");

		org.mockito.Mockito.reset(queryRepository);   // 스텁을 지우면 스파이는 실제 동작으로 돌아간다
		assertThat(reconciliationService.run(ReconciliationRun.Trigger.MANUAL).getStatus())
			.isEqualTo(ReconciliationRun.Status.SUCCEEDED);
	}

	@Test
	void 원장은_잔고를_바꾸는_트랜잭션_밖에서_따로_기록할_수_없다() {
		Long account = createAccount();

		assertThatThrownBy(() -> cashLedgerService.record(account, CashLedgerType.QUEST_REWARD, BigDecimal.TEN, null))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	private Long createAccount() {
		UUID userId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO users (user_id, email, nickname, password, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'password', NOW(), NOW())", userId, "ledger-" + userId + "@test.com",
			"ledger-" + userId.toString().substring(0, 8));
		Long id = accountService.createAccount(userId).getVirtualAccountId();
		accounts.add(id);
		return id;
	}

	private List<Map<String, Object>> mismatchesOf(ReconciliationRun run, Long account) {
		return jdbcTemplate.queryForList("SELECT mismatch_type, order_id, expected, actual FROM reconciliation_mismatch "
			+ "WHERE run_id = ? AND virtual_account_id = ?", run.getRunId(), account);
	}

	private BigDecimal balance(Long account) {
		return jdbcTemplate.queryForObject("SELECT balance FROM virtual_account WHERE virtual_account_id = ?",
			BigDecimal.class, account);
	}

	private BigDecimal ledgerSum(Long account) {
		return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM cash_ledger WHERE virtual_account_id = ?",
			BigDecimal.class, account);
	}

	private List<String> ledgerTypes(Long account) {
		return jdbcTemplate.queryForList("SELECT DISTINCT entry_type FROM cash_ledger WHERE virtual_account_id = ?",
			String.class, account);
	}

	private int orderCount() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
	}
}
