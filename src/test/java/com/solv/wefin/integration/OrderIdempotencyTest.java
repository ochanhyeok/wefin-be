package com.solv.wefin.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.math.BigDecimal;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import com.solv.wefin.domain.trading.market.service.MarketService;
import com.solv.wefin.domain.trading.order.dto.OrderResult;
import com.solv.wefin.domain.trading.order.scheduler.OrderIdempotencyCleanupScheduler;
import com.solv.wefin.domain.trading.order.dto.IdempotencyClaim;
import com.solv.wefin.domain.trading.order.service.MarketOrderFacade;
import com.solv.wefin.domain.trading.order.service.OrderIdempotencyService;
import com.solv.wefin.domain.trading.order.service.OrderService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

/**
 * 멱등 주문의 실패 시나리오. 각 테스트는 "같은 키로 다시 보냈을 때 무엇이 돌아와야 하는가"를 검증한다.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderIdempotencyTest extends IntegrationTestBase {

	private static final BigDecimal PRICE = new BigDecimal("100000");

	@Autowired
	private MarketOrderFacade facade;
	@Autowired
	private VirtualAccountService accountService;
	@Autowired
	private OrderIdempotencyCleanupScheduler cleanupScheduler;
	@Autowired
	private OrderIdempotencyService idempotencyService;
	@Autowired
	private OrderService orderService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private MarketService marketPriceProvider;   // MarketPriceProvider 구현체. 구체 타입으로 주입받는 곳이 있어 구현체째 대체
	@MockitoBean
	private QuestProgressService questProgressService;

	private Long stockId;
	private Long accountId;
	private Long otherAccountId;

	@BeforeEach
	void setUp() {
		given(marketPriceProvider.getCurrentPrice(anyString())).willReturn(PRICE);
		stockId = jdbcTemplate.queryForObject("SELECT stock_id FROM stock WHERE stock_code = '005930'", Long.class);
		accountId = createAccount();
		otherAccountId = createAccount();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.execute("DELETE FROM trade");
		jdbcTemplate.execute("DELETE FROM orders");
		jdbcTemplate.execute("DELETE FROM order_idempotency");
		jdbcTemplate.execute("DELETE FROM portfolio");
		jdbcTemplate.execute("DELETE FROM virtual_account");
		jdbcTemplate.execute("DELETE FROM users WHERE email LIKE 'idem-%'");
	}

	@Test
	void 같은_키로_다시_보내면_주문을_새로_만들지_않고_첫_응답을_돌려준다() {
		String key = newKey();
		OrderResult first = facade.buyMarket(accountId, stockId, 3, key);
		facade.buyMarket(accountId, stockId, 1, newKey());   // 사이에 다른 주문으로 잔고가 바뀜

		OrderResult retry = facade.buyMarket(accountId, stockId, 3, key);

		assertThat(first.replayed()).isFalse();
		assertThat(retry.replayed()).isTrue();
		assertThat(retry.info().order().getOrderNo()).isEqualTo(first.info().order().getOrderNo());
		// 재전송은 "지금 잔고"가 아니라 첫 응답 당시 잔고를 돌려준다
		assertThat(retry.info().balance()).isEqualByComparingTo(first.info().balance());
		assertThat(orderCount(accountId)).isEqualTo(2);
	}

	@Test
	void 같은_키로_내용이_다른_주문을_보내면_422() {
		String key = newKey();
		facade.buyMarket(accountId, stockId, 3, key);

		assertErrorCode(() -> facade.buyMarket(accountId, stockId, 5, key), ErrorCode.ORDER_IDEMPOTENCY_KEY_REUSED);
		assertThat(orderCount(accountId)).isEqualTo(1);
	}

	@Test
	void 키는_계좌별이라_다른_계좌가_같은_키를_써도_서로_막지_않는다() {
		String key = newKey();

		OrderResult mine = facade.buyMarket(accountId, stockId, 1, key);
		OrderResult other = facade.buyMarket(otherAccountId, stockId, 1, key);

		assertThat(mine.replayed()).isFalse();
		assertThat(other.replayed()).isFalse();
		assertThat(other.info().order().getOrderNo()).isNotEqualTo(mine.info().order().getOrderNo());
	}

	@Test
	void 잔고_부족_거절은_기록되어_잔고를_채운_뒤_같은_키로_보내도_같은_거절() {
		String key = newKey();
		assertErrorCode(() -> facade.buyMarket(accountId, stockId, 200, key), ErrorCode.ORDER_INSUFFICIENT_BALANCE);

		accountService.depositBalance(accountId, new BigDecimal("100000000"), com.solv.wefin.domain.trading.ledger.entity.CashLedgerType.QUEST_REWARD);

		// 거절도 확정된 결과라 같은 키는 같은 답을 받는다. 다시 사려면 새 키(새 의도)로 보낸다
		assertErrorCode(() -> facade.buyMarket(accountId, stockId, 200, key), ErrorCode.ORDER_INSUFFICIENT_BALANCE);
		assertThat(facade.buyMarket(accountId, stockId, 200, newKey()).replayed()).isFalse();
		assertThat(status(accountId, key)).isEqualTo("FAILED");
	}

	@Test
	void 시세_장애처럼_일시적_오류면_선점을_풀어_같은_키로_재시도할_수_있다() {
		String key = newKey();
		given(marketPriceProvider.getCurrentPrice(anyString())).willReturn(null);   // → MARKET_API_FAILED(503)
		assertErrorCode(() -> facade.buyMarket(accountId, stockId, 1, key), ErrorCode.MARKET_API_FAILED);
		assertThat(rowCount(accountId, key)).isZero();

		given(marketPriceProvider.getCurrentPrice(anyString())).willReturn(PRICE);
		OrderResult retry = facade.buyMarket(accountId, stockId, 1, key);

		assertThat(retry.replayed()).isFalse();
		assertThat(orderCount(accountId)).isEqualTo(1);
	}

	@Test
	void 예상_못한_런타임_오류로_롤백되어도_선점을_풀어_재시도할_수_있다() {
		String key = newKey();
		given(marketPriceProvider.getCurrentPrice(anyString())).willThrow(new IllegalStateException("boom"));
		assertThatThrownBy(() -> facade.buyMarket(accountId, stockId, 1, key)).isInstanceOf(IllegalStateException.class);
		assertThat(rowCount(accountId, key)).isZero();
		assertThat(orderCount(accountId)).isZero();

		willReturn(PRICE).given(marketPriceProvider).getCurrentPrice(anyString());   // 예외 스텁 위에 덮어쓰기
		assertThat(facade.buyMarket(accountId, stockId, 1, key).replayed()).isFalse();
	}

	@Test
	void 처리_중인_키로_다시_보내면_409() {
		String key = newKey();
		insertProcessing(accountId, key, 1, "now()");

		assertErrorCode(() -> facade.buyMarket(accountId, stockId, 1, key), ErrorCode.ORDER_REQUEST_IN_PROGRESS);
		assertThat(orderCount(accountId)).isZero();
	}

	@Test
	void 처리하던_서버가_죽어_PROCESSING이_리스를_넘기면_같은_키로_다시_처리한다() {
		String key = newKey();
		insertProcessing(accountId, key, 1, "now() - interval '2 minutes'");

		OrderResult result = facade.buyMarket(accountId, stockId, 1, key);

		assertThat(result.replayed()).isFalse();
		assertThat(status(accountId, key)).isEqualTo("DONE");
	}

	@Test
	void 리스가_끝나_다른_요청이_재선점하면_늦게_끝난_원래_요청은_롤백된다() {
		String key = newKey();
		IdempotencyClaim original = idempotencyService.claim(accountId, key, fingerprint(1)).claim();   // 원래 요청 선점
		jdbcTemplate.update("UPDATE order_idempotency SET updated_at = now() - interval '2 minutes' "
			+ "WHERE virtual_account_id = ? AND idempotency_key = ?", accountId, key);              // 원래 요청이 리스를 넘김

		assertThat(facade.buyMarket(accountId, stockId, 1, key).replayed()).isFalse();             // 재시도가 재선점해 주문
		BigDecimal balanceAfterRetry = balance(accountId);

		// 원래 요청이 뒤늦게 주문을 끝내려 해도 토큰이 달라 완료 기록이 0건 → 주문 트랜잭션 롤백
		assertErrorCode(() -> orderService.buyMarket(accountId, stockId, 1, PRICE, original),
			ErrorCode.ORDER_REQUEST_IN_PROGRESS);
		assertThat(orderCount(accountId)).isEqualTo(1);
		assertThat(balance(accountId)).isEqualByComparingTo(balanceAfterRetry);
		assertThat(status(accountId, key)).isEqualTo("DONE");
	}

	@Test
	void 매도도_리스가_넘어간_뒤_늦게_끝난_원래_요청은_롤백되어_보유_수량을_두_번_빼지_않는다() {
		facade.buyMarket(accountId, stockId, 2, newKey());
		String key = newKey();
		IdempotencyClaim original = idempotencyService.claim(accountId, key, fingerprint("SELL", 1)).claim();
		jdbcTemplate.update("UPDATE order_idempotency SET updated_at = now() - interval '2 minutes' "
			+ "WHERE virtual_account_id = ? AND idempotency_key = ?", accountId, key);

		assertThat(facade.sellMarket(accountId, stockId, 1, key).replayed()).isFalse();
		BigDecimal balanceAfterRetry = balance(accountId);

		assertErrorCode(() -> orderService.sellMarket(accountId, stockId, 1, PRICE, original),
			ErrorCode.ORDER_REQUEST_IN_PROGRESS);
		assertThat(orderCount(accountId)).isEqualTo(2);   // 매수 1 + 매도 1
		assertThat(balance(accountId)).isEqualByComparingTo(balanceAfterRetry);
		assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM portfolio WHERE virtual_account_id = ? AND stock_id = ?",
			Integer.class, accountId, stockId)).isEqualTo(1);
		assertThat(status(accountId, key)).isEqualTo("DONE");
	}

	@Test
	void 입력_오류는_선점보다_먼저_걸러서_고친_요청이_같은_키로_통과한다() {
		String key = newKey();
		assertErrorCode(() -> facade.buyMarket(accountId, stockId, 0, key), ErrorCode.ORDER_INVALID_QUANTITY);
		assertThat(rowCount(accountId, key)).isZero();

		assertThat(facade.buyMarket(accountId, stockId, 1, key).replayed()).isFalse();
	}

	@Test
	void 키가_없거나_UUID가_아니면_400() {
		assertErrorCode(() -> facade.buyMarket(accountId, stockId, 1, null), ErrorCode.ORDER_IDEMPOTENCY_KEY_REQUIRED);
		assertErrorCode(() -> facade.buyMarket(accountId, stockId, 1, " "), ErrorCode.ORDER_IDEMPOTENCY_KEY_REQUIRED);
		assertErrorCode(() -> facade.buyMarket(accountId, stockId, 1, "abc"), ErrorCode.ORDER_IDEMPOTENCY_KEY_INVALID);
		assertErrorCode(() -> facade.buyMarket(accountId, stockId, 1, "1-1-1-1-1"), ErrorCode.ORDER_IDEMPOTENCY_KEY_INVALID);
	}

	@Test
	void 정리_배치는_24시간이_지난_기록만_지운다() {
		String expired = newKey();
		String recent = newKey();
		insertProcessing(accountId, expired, 1, "now() - interval '25 hours'");
		insertProcessing(accountId, recent, 1, "now() - interval '23 hours'");

		int deleted = cleanupScheduler.purgeExpired();

		assertThat(deleted).isEqualTo(1);
		assertThat(rowCount(accountId, expired)).isZero();
		assertThat(rowCount(accountId, recent)).isEqualTo(1);
	}

	@Test
	void 정리_배치는_오래전에_생성됐어도_재선점되어_처리_중인_기록은_지우지_않는다() {
		String reclaimed = newKey();
		insertProcessing(accountId, reclaimed, 1, "now() - interval '25 hours'");
		jdbcTemplate.update("UPDATE order_idempotency SET updated_at = now() "
			+ "WHERE virtual_account_id = ? AND idempotency_key = ?", accountId, reclaimed);   // 리스 안에서 재선점됨

		cleanupScheduler.purgeExpired();

		assertThat(rowCount(accountId, reclaimed)).isEqualTo(1);
	}

	private Long createAccount() {
		UUID userId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO users (user_id, email, nickname, password, created_at, updated_at) "
			+ "VALUES (?, ?, ?, 'password', NOW(), NOW())", userId, "idem-" + userId + "@test.com",
			"idem-" + userId.toString().substring(0, 8));
		return accountService.createAccount(userId).getVirtualAccountId();
	}

	/** 같은 요청이 만들었을 지문으로 PROCESSING 기록을 직접 심는다 (서버가 처리 중이거나 처리 중 죽은 상황) */
	private void insertProcessing(Long account, String key, int quantity, String timestampSql) {
		jdbcTemplate.update("INSERT INTO order_idempotency (virtual_account_id, idempotency_key, request_fingerprint, "
			+ "status, created_at, updated_at) VALUES (?, ?, ?, 'PROCESSING', " + timestampSql + ", " + timestampSql + ")",
			account, key, fingerprint(quantity));
	}

	/** 시장가 매수 요청의 지문. 파사드와 같은 규칙(SHA-256 of "BUY|MARKET|종목|수량|") */
	private String fingerprint(int quantity) {
		return fingerprint("BUY", quantity);
	}

	private String fingerprint(String side, int quantity) {
		return jdbcTemplate.queryForObject("SELECT encode(sha256(convert_to(?, 'UTF8')), 'hex')", String.class,
			side + "|MARKET|" + stockId + "|" + quantity + "|");
	}

	private BigDecimal balance(Long account) {
		return jdbcTemplate.queryForObject("SELECT balance FROM virtual_account WHERE virtual_account_id = ?",
			BigDecimal.class, account);
	}

	private int orderCount(Long account) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE virtual_account_id = ?", Integer.class, account);
	}

	private int rowCount(Long account, String key) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_idempotency "
			+ "WHERE virtual_account_id = ? AND idempotency_key = ?", Integer.class, account, key);
	}

	private String status(Long account, String key) {
		return jdbcTemplate.queryForObject("SELECT status FROM order_idempotency "
			+ "WHERE virtual_account_id = ? AND idempotency_key = ?", String.class, account, key);
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

	private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
		assertThatThrownBy(call)
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(expected);
	}
}
