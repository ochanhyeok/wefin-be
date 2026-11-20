package com.solv.wefin.domain.trading.order.service;

import static com.solv.wefin.domain.trading.common.TradingConstants.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.solv.wefin.domain.quest.entity.QuestEventType;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.domain.trading.common.TradingConstants;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.solv.wefin.domain.trading.account.entity.VirtualAccount;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.common.MarketPriceProvider;
import com.solv.wefin.domain.trading.common.StockInfoProvider;
import com.solv.wefin.domain.trading.matching.event.OrderMatchedEvent;
import com.solv.wefin.domain.trading.order.dto.OrderCancelInfo;
import com.solv.wefin.domain.trading.order.dto.OrderInfo;
import com.solv.wefin.domain.trading.order.dto.OrderSearchCondition;
import com.solv.wefin.domain.trading.order.entity.Order;
import com.solv.wefin.domain.trading.order.entity.OrderSide;
import com.solv.wefin.domain.trading.order.entity.OrderType;
import com.solv.wefin.domain.trading.order.repository.OrderIdempotencyRepository;
import com.solv.wefin.domain.trading.order.repository.OrderRepository;
import com.solv.wefin.domain.trading.portfolio.entity.Currency;
import com.solv.wefin.domain.trading.portfolio.entity.Portfolio;
import com.solv.wefin.domain.trading.portfolio.service.PortfolioService;
import com.solv.wefin.domain.trading.stock.entity.Stock;
import com.solv.wefin.domain.trading.trade.service.TradeService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class OrderService {

	private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:order:";
	private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(15);

	private final OrderRepository orderRepository;
	private final OrderIdempotencyRepository orderIdempotencyRepository;
	private final PortfolioService portfolioService;
	private final VirtualAccountService virtualAccountService;
	private final MarketPriceProvider marketPriceProvider;
	private final StockInfoProvider stockInfoProvider;
	private final TradeService tradeService;
	private final ApplicationEventPublisher eventPublisher;
	private final QuestProgressService questProgressService;
	private final StringRedisTemplate redisTemplate;

	/** 멱등키 없이 호출되는 경로 — 요청마다 새 키를 발급한다(중복 식별 불가, 기존 동작 유지). */
	@Transactional
	public OrderInfo buyMarket(Long virtualAccountId, Long stockId, Integer quantity) {
		return buyMarket(virtualAccountId, stockId, quantity, UUID.randomUUID().toString());
	}

	/**
	 * 시장가 매수 — 멱등키로 따닥(중복 요청)을 방어한다.
	 *
	 * <p>트랜잭션 시작 시 멱등키를 PROCESSING 으로 선점 INSERT 한다. 같은 키로 들어온 동시 요청은
	 * DB UNIQUE 가 직렬화해 정확히 하나만 통과하고, 나머지는 부수효과(잔고 차감) 발생 전에 거부된다.</p>
	 */
	@Transactional
	public OrderInfo buyMarket(Long virtualAccountId, Long stockId, Integer quantity, String idempotencyKey) {
		// 0. 멱등 선점 (따닥 방어) — 이미 처리 중/처리된 키면 거부
		claimIdempotency(idempotencyKey);

		// 1. 수량 검증
		validateQuantity(quantity);

		// 2. 현재가 조회
		Stock stock = stockInfoProvider.getStock(stockId);
		BigDecimal currentPrice = marketPriceProvider.getCurrentPrice(stock.getStockCode());
		if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
			throw new BusinessException(ErrorCode.MARKET_API_FAILED);
		}

		// 3. 금액 계산
		BigDecimal totalAmount = currentPrice.multiply(BigDecimal.valueOf(quantity));

		// 4. 수수료 계산 (totalAmount x 0.00015, 절사)
		BigDecimal fee = totalAmount.multiply(FEE_RATE).setScale(0, RoundingMode.DOWN);

		// 5. 예수금 차감 (totalAmount + fee)
		VirtualAccount account = virtualAccountService.deductBalance(virtualAccountId, totalAmount.add(fee));

		// 6. Order 생성 + 저장
		Order order = orderRepository.save(new Order(virtualAccountId, stockId, OrderType.MARKET, OrderSide.BUY, quantity,
			null, Currency.KRW, null, fee, BigDecimal.ZERO));

		// 7. Trade 생성 + 저장
		tradeService.createBuyTrade(order.getOrderId(), virtualAccountId, stockId,
			quantity, currentPrice, totalAmount, fee, Currency.KRW, null);
		order.fill(quantity);
		OffsetDateTime matchedAt = OffsetDateTime.now();

		// 8. 포트폴리오 갱신
		portfolioService.addHolding(virtualAccountId, stockId, quantity, currentPrice, Currency.KRW);

		// 9. 이벤트 발행
		eventPublisher.publishEvent(OrderMatchedEvent.ofBuy(
			OrderType.MARKET, order.getOrderNo(), stock.getStockCode(), stock.getStockName(),
			quantity, currentPrice, fee, account.getBalance(), matchedAt
		));

		questProgressService.handleEvent(account.getUserId(), QuestEventType.BUY_STOCK);

		// 멱등 레코드에 결과(주문) 기록 → DONE
		orderIdempotencyRepository.markDone(idempotencyKey, order.getOrderId());

		return new OrderInfo(order, stock.getStockCode(), stock.getStockName(), currentPrice,
			totalAmount, BigDecimal.ZERO, BigDecimal.ZERO, account.getBalance());
	}

	/**
	 * 멱등키 선점. 처음 보는 키면 PROCESSING 으로 INSERT 하고, 이미 있으면 중복으로 거부한다.
	 * 동시 따닥은 DB UNIQUE 가 직렬화하므로 정확히 한 요청만 선점에 성공한다.
	 */
	private void claimIdempotency(String idempotencyKey) {
		// 1차: Redis SETNX — DB까지 가지 않고 차단 (멀티 인스턴스에서 DB 부하 감소)
		String redisKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
		Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", IDEMPOTENCY_TTL);
		if (Boolean.FALSE.equals(acquired)) {
			throw new BusinessException(ErrorCode.ORDER_DUPLICATE_REQUEST);
		}
		// 2차: DB UNIQUE 백스톱 — Redis 장애·TTL 만료 시에도 중복 차단 보장
		try {
			orderIdempotencyRepository.insertClaim(idempotencyKey);
		} catch (DataIntegrityViolationException e) {
			throw new BusinessException(ErrorCode.ORDER_DUPLICATE_REQUEST);
		}
	}

	@Transactional
	public OrderInfo sellMarket(Long virtualAccountId, Long stockId, Integer quantity) {
		// 1. 수량 검증
		validateQuantity(quantity);

		// 2. 종목 조회
		Stock stock = stockInfoProvider.getStock(stockId);

		// 3. 현재가 조회
		BigDecimal currentPrice = marketPriceProvider.getCurrentPrice(stock.getStockCode());
		if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
			throw new BusinessException(ErrorCode.MARKET_API_FAILED);
		}

		// 계좌 락 선점
		VirtualAccount account = virtualAccountService.getAccountWithLock(virtualAccountId);

		// 4. 보유 종목 확인
		Portfolio portfolio = portfolioService.getPortfolioForUpdate(virtualAccountId, stockId);
		if (portfolio.getQuantity() < quantity) {
			throw new BusinessException(ErrorCode.ORDER_INSUFFICIENT_HOLDINGS);
		}
		BigDecimal avgPrice = portfolio.getAvgPrice();

		// 5. 금액 계산
		BigDecimal totalAmount = currentPrice.multiply(BigDecimal.valueOf(quantity));

		// 6. 수수료 계산
		BigDecimal fee = totalAmount.multiply(FEE_RATE).setScale(0, RoundingMode.DOWN);

		// 7. 매도 세금 계산
		BigDecimal tax = totalAmount.multiply(TAX_RATE).setScale(0, RoundingMode.DOWN);

		// 8. 실현손익 계산
		BigDecimal realizedAmount = currentPrice.subtract(avgPrice).multiply(BigDecimal.valueOf(quantity));

		// 9. Order 생성 + 저장
		Order order = orderRepository.save(
			new Order(virtualAccountId, stockId, OrderType.MARKET, OrderSide.SELL, quantity,
				null, Currency.KRW, null, fee, tax));

		// 10. Trade 생성 + 저장
		tradeService.createSellTrade(order.getOrderId(), virtualAccountId, stockId, quantity, currentPrice,
			totalAmount, fee, tax, realizedAmount, Currency.KRW, null);

		// 11. Order 상태 변경
		order.fill(quantity);
		OffsetDateTime matchedAt = OffsetDateTime.now();

		// 12. 포트폴리오 수량 차감
		portfolioService.deductQuantity(virtualAccountId, stockId, quantity);

		// 13. 예수금 입금
		account.deposit(totalAmount.subtract(fee).subtract(tax));

		// 14. 실현손익 누적
		account.addProfit(realizedAmount);

		// 15. 이벤트 발행
		eventPublisher.publishEvent(OrderMatchedEvent.ofSell(
			OrderType.MARKET, order.getOrderNo(), stock.getStockCode(), stock.getStockName(),
			quantity, currentPrice, fee, tax, realizedAmount, account.getBalance(), matchedAt
		));

		try {
			questProgressService.handleEvent(account.getUserId(), QuestEventType.SELL_STOCK);
		} catch (RuntimeException e) {
			log.warn("퀘스트 진행도 반영 실패 userId={}", account.getUserId(), e);
		}

		return new OrderInfo(order, stock.getStockCode(), stock.getStockName(), currentPrice,
			totalAmount, tax, realizedAmount, account.getBalance());
	}

	/**
	 * 미체결 주문을 취소한다.
	 * BUY: 예약금 (requestPrice x quantity + fee) 환불
	 * SELL: 예약 수량을 포트폴리오에 반환
	 */
	@Transactional
	public OrderCancelInfo cancelOrder(Long virtualAccountId, UUID orderNo) {
		Order order = orderRepository.findByOrderNoForUpdate(orderNo)
			.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
		if (order.getOrderType() == OrderType.MARKET) {
			throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE);
		}

		order.validateOwnership(virtualAccountId);
		VirtualAccount account = virtualAccountService.getAccountWithLock(virtualAccountId);

		BigDecimal refundedAmount = BigDecimal.ZERO;
		if (order.getSide() == OrderSide.BUY) {
			int remainingQuantity = order.getQuantity() - order.getFilledQuantity();
			BigDecimal remainingCost = order.getRequestPrice()
					.multiply(BigDecimal.valueOf(remainingQuantity));
			BigDecimal remainingFee = remainingCost
					.multiply(TradingConstants.FEE_RATE)
					.setScale(0, RoundingMode.DOWN);
			refundedAmount = remainingCost.add(remainingFee);
			account.deposit(refundedAmount);
		} else if (order.getSide() == OrderSide.SELL) {
			int remainingQuantity = order.getQuantity() - order.getFilledQuantity();
			portfolioService.addHolding(virtualAccountId, order.getStockId(), remainingQuantity,
				order.getReservedAvgPrice(), order.getCurrency());
		}

		order.cancel();
		return new OrderCancelInfo(order, refundedAmount, account.getBalance());
	}

	/**
	 * 미체결 주문의 가격/수량을 정정한다.
	 * BUY: 예약금 차액을 계좌에서 추가 차감 또는 환불
	 * SELL: 수량 변경분만큼 포트폴리오 조정
	 * 시장가 주문은 정정 불가.
	 */
	@Transactional
	public OrderInfo modifyOrder(Long virtualAccountId, UUID orderNo, BigDecimal newPrice, Integer newQuantity) {
		Order order = orderRepository.findByOrderNoForUpdate(orderNo)
			.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
		if (order.getOrderType() == OrderType.MARKET) {
			throw new BusinessException(ErrorCode.ORDER_NOT_MODIFIABLE);
		}
		if (order.getFilledQuantity() > 0) {
			throw new BusinessException(ErrorCode.ORDER_PARTIAL_NOT_MODIFIABLE);
		}

		order.validateOwnership(virtualAccountId);
		VirtualAccount account = virtualAccountService.getAccountWithLock(virtualAccountId);
		Integer oldQuantity = order.getQuantity();

		BigDecimal oldReserved = order.getRequestPrice()
			.multiply(BigDecimal.valueOf(order.getQuantity()))
			.add(order.getFee());

		order.modify(newPrice, newQuantity);

		BigDecimal newReserved = newPrice.multiply(BigDecimal.valueOf(newQuantity)).add(order.getFee());

		if (order.getSide() == OrderSide.BUY) {
			BigDecimal diff = newReserved.subtract(oldReserved);
			if (diff.compareTo(BigDecimal.ZERO) > 0) {
				account.deduct(diff);
			} else if (diff.compareTo(BigDecimal.ZERO) < 0) {
				account.deposit(diff.abs());
			}
		} else if (order.getSide() == OrderSide.SELL) {
			int diffQuantity = Math.abs(newQuantity - oldQuantity);
			if (newQuantity > oldQuantity) {
				// 현재 포트폴리오 avgPrice 조회 (deductQuantity는 락 보유 상태)
				Portfolio portfolio = portfolioService.getPortfolioForUpdate(
						virtualAccountId, order.getStockId());
				BigDecimal currentAvgPrice = portfolio.getAvgPrice();

				// 가중평균으로 reservedAvgPrice 갱신
				// newReservedAvg = (oldQty × oldReservedAvg + diffQty × currentAvg) / newQty
				BigDecimal oldReservedAvg = order.getReservedAvgPrice();
				BigDecimal weightedSum = oldReservedAvg
						.multiply(BigDecimal.valueOf(oldQuantity))
						.add(currentAvgPrice.multiply(BigDecimal.valueOf(diffQuantity)));
				BigDecimal newReservedAvg = weightedSum
						.divide(BigDecimal.valueOf(newQuantity), 2, RoundingMode.HALF_UP);
				order.updateReservedAvgPrice(newReservedAvg);

				portfolioService.deductQuantity(virtualAccountId, order.getStockId(), diffQuantity);
			} else if (newQuantity < oldQuantity) {
				portfolioService.addHolding(virtualAccountId, order.getStockId(), diffQuantity,
						order.getReservedAvgPrice(), order.getCurrency());
				// reservedAvgPrice 그대로 (수량 감소는 기존 스냅샷 유지)
			}
		}
		Stock stock = stockInfoProvider.getStock(order.getStockId());
		BigDecimal totalAmount = newPrice.multiply(BigDecimal.valueOf(newQuantity));

		return new OrderInfo(order, stock.getStockCode(), stock.getStockName(),
			newPrice, totalAmount, order.getTax(), BigDecimal.ZERO, account.getBalance());
	}

	public List<Order> searchOrders(Long virtualAccountId, OrderSearchCondition condition,
									Long cursor, int size) {
		return orderRepository.searchOrders(virtualAccountId, condition, cursor, size);
	}

	public List<Order> findPendingOrders(Long virtualAccountId) {
		return orderRepository.findPendingOrders(virtualAccountId);
	}

	public List<Order> findTodayFilledOrders(Long virtualAccountId) {
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		return orderRepository.findTodayFilledOrders(virtualAccountId, today);
	}

	private static void validateQuantity(Integer quantity) {
		if (quantity == null || quantity <= 0) {
			throw new BusinessException(ErrorCode.ORDER_INVALID_QUANTITY);
		}
	}
}
