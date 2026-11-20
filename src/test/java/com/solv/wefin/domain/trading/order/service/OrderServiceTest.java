package com.solv.wefin.domain.trading.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.solv.wefin.domain.quest.entity.QuestEventType;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.domain.trading.account.entity.VirtualAccount;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.common.MarketPriceProvider;
import com.solv.wefin.domain.trading.common.StockInfoProvider;
import com.solv.wefin.domain.trading.matching.event.OrderMatchedEvent;
import com.solv.wefin.domain.trading.order.entity.Order;
import com.solv.wefin.domain.trading.order.entity.OrderSide;
import com.solv.wefin.domain.trading.order.entity.OrderStatus;
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

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private TradeService tradeService;
	@Mock
	private VirtualAccountService virtualAccountService;
	@Mock
	private PortfolioService portfolioService;
	@Mock
	private MarketPriceProvider marketPriceProvider;
	@Mock
	private StockInfoProvider stockInfoProvider;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private QuestProgressService questProgressService;
	@Mock
	private OrderIdempotencyRepository orderIdempotencyRepository;
	@Mock
	private StringRedisTemplate redisTemplate;
	@Mock
	private ValueOperations<String, String> valueOperations;

	@InjectMocks
	private OrderService orderService;

	/** 매수 경로는 멱등키 선점(Redis SETNX)을 먼저 통과해야 한다 */
	private void givenIdempotencyClaimSucceeds() {
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
	}

	@Test
	void 매수_성공() {
		// given
		givenIdempotencyClaimSucceeds();
		Stock mockStock = mock(Stock.class);

		given(stockInfoProvider.getStock(1L)).willReturn(mockStock);
		given(mockStock.getStockCode()).willReturn("005930");
		given(marketPriceProvider.getCurrentPrice("005930"))
			.willReturn(new BigDecimal("170000"));
		given(virtualAccountService.deductBalance(eq(1L), any()))
			.willReturn(new VirtualAccount(UUID.randomUUID()));
		given(orderRepository.save(any()))
			.willAnswer(invocation -> invocation.getArgument(0));

		// when
		orderService.buyMarket(1L, 1L, 20);

		// then
		verify(orderRepository).save(any());
		verify(tradeService).createBuyTrade(any(), eq(1L), eq(1L), eq(20), any(), any(), any(), any(), any());
		verify(portfolioService).addHolding(eq(1L), eq(1L), eq(20), any(), any());
		verify(eventPublisher).publishEvent(any(OrderMatchedEvent.class));
		verify(questProgressService).handleEvent(any(UUID.class), eq(QuestEventType.BUY_STOCK));
	}

	@Test
	void 수량_0_이하() {
		// given
		givenIdempotencyClaimSucceeds();

		// when & then
		assertThatThrownBy(() -> orderService.buyMarket(1L, 1L, 0))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	void 예수금_부족() {
		// given
		givenIdempotencyClaimSucceeds();
		Stock mockStock = mock(Stock.class);
		given(stockInfoProvider.getStock(1L)).willReturn(mockStock);
		given(mockStock.getStockCode()).willReturn("005930");
		given(marketPriceProvider.getCurrentPrice("005930"))
			.willReturn(new BigDecimal("178000"));
		given(virtualAccountService.deductBalance(any(), any()))
			.willThrow(new BusinessException(ErrorCode.ORDER_INSUFFICIENT_BALANCE));

		// when & then
		assertThatThrownBy(() -> orderService.buyMarket(1L, 1L, 20))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	void 매도_성공() {
		// given
		Stock mockStock = mock(Stock.class);
		given(stockInfoProvider.getStock(1L)).willReturn(mockStock);
		given(mockStock.getStockCode()).willReturn("005930");
		given(marketPriceProvider.getCurrentPrice("005930"))
			.willReturn(new BigDecimal("178000"));

		VirtualAccount mockAccount = mock(VirtualAccount.class);
		given(virtualAccountService.getAccountWithLock(1L))
			.willReturn(mockAccount);
		given(mockAccount.getBalance()).willReturn(new BigDecimal("9000000"));
		given(mockAccount.getUserId()).willReturn(UUID.randomUUID());

		Portfolio mockPortfolio = mock(Portfolio.class);
		given(mockPortfolio.getAvgPrice()).willReturn(new BigDecimal("170000"));
		given(mockPortfolio.getQuantity()).willReturn(20);
		given(portfolioService.getPortfolioForUpdate(1L, 1L))
			.willReturn(mockPortfolio);

		given(orderRepository.save(any()))
			.willAnswer(invocation -> invocation.getArgument(0));

		// when
		orderService.sellMarket(1L, 1L, 10);

		// then
		verify(tradeService).createSellTrade(any(), eq(1L), eq(1L), eq(10),
			any(), any(), any(), any(), any(), any(), any());
		verify(portfolioService).deductQuantity(eq(1L), eq(1L), eq(10));
		verify(eventPublisher).publishEvent(any(OrderMatchedEvent.class));
		verify(questProgressService).handleEvent(any(UUID.class), eq(QuestEventType.SELL_STOCK));
	}

	@Test
	void 매도_미보유_종목() {
		// given
		Stock mockStock = mock(Stock.class);
		given(stockInfoProvider.getStock(1L)).willReturn(mockStock);
		given(mockStock.getStockCode()).willReturn("005930");
		given(marketPriceProvider.getCurrentPrice("005930"))
			.willReturn(new BigDecimal("178000"));
		given(portfolioService.getPortfolioForUpdate(1L, 1L))
			.willThrow(new BusinessException(ErrorCode.ORDER_STOCK_NOT_HELD));
		given(virtualAccountService.getAccountWithLock(1L))
			.willReturn(mock(VirtualAccount.class));

		// when & then
		assertThatThrownBy(() -> orderService.sellMarket(1L, 1L, 10))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	void 매도_보유수량_부족() {
		// given
		Stock mockStock = mock(Stock.class);
		given(stockInfoProvider.getStock(1L)).willReturn(mockStock);
		given(mockStock.getStockCode()).willReturn("005630");
		given(marketPriceProvider.getCurrentPrice("005630"))
			.willReturn(new BigDecimal("178000"));
		given(virtualAccountService.getAccountWithLock(1L))
			.willReturn(mock(VirtualAccount.class));

		Portfolio mockPortfolio = mock(Portfolio.class);
		given(mockPortfolio.getQuantity()).willReturn(20);
		given(portfolioService.getPortfolioForUpdate(1L, 1L))
			.willReturn(mockPortfolio);

		// when & then
		assertThatThrownBy(() -> orderService.sellMarket(1L, 1L, 30))
			.isInstanceOf(BusinessException.class);
	}

	@Nested
	class CancelOrderTest{

		@Test
		void 취소_성공_매수주문() {
			// given
			Order order = new Order(1L, 1L, OrderType.LIMIT, OrderSide.BUY, 10,
				BigDecimal.valueOf(50000), Currency.KRW, null,
				BigDecimal.valueOf(75), BigDecimal.ZERO);
			VirtualAccount mockAccount = mock(VirtualAccount.class);
			given(orderRepository.findByOrderNoForUpdate(any())).willReturn(Optional.of(order));
			given(virtualAccountService.getAccountWithLock(1L)).willReturn(mockAccount);

			// when
			orderService.cancelOrder(1L, order.getOrderNo());

			// then
			verify(mockAccount).deposit(BigDecimal.valueOf(500075));
			assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		}

		@Test
		void 취소_성공_매도주문() {
			// given
			BigDecimal reservedAvgPrice = BigDecimal.valueOf(4500);  // 포트폴리오 avgPrice 스냅샷
			Order order = new Order(1L, 1L, OrderType.LIMIT, OrderSide.SELL, 10,
				BigDecimal.valueOf(5000), Currency.KRW, null,
				BigDecimal.valueOf(75), BigDecimal.ZERO, reservedAvgPrice);
			VirtualAccount mockAccount = mock(VirtualAccount.class);
			given(orderRepository.findByOrderNoForUpdate(order.getOrderNo())).willReturn(Optional.of(order));
			given(virtualAccountService.getAccountWithLock(1L)).willReturn(mockAccount);

			// when
			orderService.cancelOrder(1L, order.getOrderNo());

			// then
			verify(portfolioService).addHolding(1L, 1L, 10, reservedAvgPrice, Currency.KRW);
			assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		}

		@Test
		void 취소_성공_매수주문_부분체결() {
			// given
			Order order = new Order(1L, 1L, OrderType.LIMIT, OrderSide.BUY, 10,
					BigDecimal.valueOf(50000), Currency.KRW, null,
					BigDecimal.valueOf(75), BigDecimal.ZERO);
			order.fillPartially(3);  // 3주 체결된 상태 → remainingQty = 7

			VirtualAccount mockAccount = mock(VirtualAccount.class);
			given(orderRepository.findByOrderNoForUpdate(any())).willReturn(Optional.of(order));
			given(virtualAccountService.getAccountWithLock(1L)).willReturn(mockAccount);

			// when
			orderService.cancelOrder(1L, order.getOrderNo());

			// then
			// remainingCost = 50000 × 7 = 350000
			// remainingFee = 350000 × 0.00015 = 52.5 → 52 (DOWN)
			// refund = 350052
			verify(mockAccount).deposit(BigDecimal.valueOf(350052));
			assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		}

		@Test
		void 취소_성공_매도주문_부분체결() {
			// given
			BigDecimal reservedAvgPrice = BigDecimal.valueOf(45000);
			Order order = new Order(1L, 1L, OrderType.LIMIT, OrderSide.SELL, 10,
					BigDecimal.valueOf(50000), Currency.KRW, null,
					BigDecimal.valueOf(75), BigDecimal.valueOf(900),
					reservedAvgPrice);
			order.fillPartially(4);  // 4주 체결 → remainingQty = 6

			VirtualAccount mockAccount = mock(VirtualAccount.class);
			given(orderRepository.findByOrderNoForUpdate(any())).willReturn(Optional.of(order));
			given(virtualAccountService.getAccountWithLock(1L)).willReturn(mockAccount);

			// when
			orderService.cancelOrder(1L, order.getOrderNo());

			// then
			// 남은 6주를 reservedAvgPrice(45000) 기준으로 복원
			verify(portfolioService).addHolding(1L, 1L, 6, reservedAvgPrice, Currency.KRW);
			assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		}

		@Test
		void 취소_실패_주문없음() {
			// given
			given(orderRepository.findByOrderNoForUpdate(any())).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> orderService.cancelOrder(1L, UUID.randomUUID()))
				.isInstanceOf(BusinessException.class)
				.hasMessage(ErrorCode.ORDER_NOT_FOUND.getMessage());
		}

		@Test
		void 취소_실패_권한없음() {
			// given
			Order order = new Order(1L, 1L, OrderType.LIMIT, OrderSide.BUY, 10,
				BigDecimal.valueOf(100000), Currency.KRW, null,
				BigDecimal.valueOf(75), BigDecimal.ZERO);
			given(orderRepository.findByOrderNoForUpdate(any())).willReturn(Optional.of(order));

			// when & then
			assertThatThrownBy(() -> orderService.cancelOrder(999L, order.getOrderNo()))
				.isInstanceOf(BusinessException.class)
				.hasMessage(ErrorCode.ORDER_OWNERSHIP_MISMATCH.getMessage());
		}
	}

	@Nested
	class ModifyOrderTest{

		@Test
		void 정정_성공_매수_가격변경() {
			// given
			Order order = new Order(1L, 1L, OrderType.LIMIT, OrderSide.BUY, 10,
				BigDecimal.valueOf(50000), Currency.KRW, null,
				BigDecimal.valueOf(75), BigDecimal.ZERO);
			VirtualAccount mockAccount = mock(VirtualAccount.class);
			Stock mockStock = mock(Stock.class);
			given(orderRepository.findByOrderNoForUpdate(any())).willReturn(Optional.of(order));
			given(virtualAccountService.getAccountWithLock(1L)).willReturn(mockAccount);
			given(stockInfoProvider.getStock(1L)).willReturn(mockStock);
			given(mockStock.getStockCode()).willReturn("005930");
			given(mockStock.getStockName()).willReturn("삼성전자");
			given(mockAccount.getBalance()).willReturn(BigDecimal.valueOf(5000000));

			// when
			orderService.modifyOrder(1L, order.getOrderNo(), BigDecimal.valueOf(60000), 10);

			// then
			assertThat(order.getRequestPrice()).isEqualByComparingTo(BigDecimal.valueOf(60000));
			verify(mockAccount).deduct(BigDecimal.valueOf(100015));
		}

		@Test
		void 정정_실패_시장가주문() {
			// given
			Order order = new Order(1L, 1L, OrderType.MARKET, OrderSide.BUY, 10,
				BigDecimal.valueOf(50000), Currency.KRW, null,
				BigDecimal.valueOf(75), BigDecimal.ZERO);
			given(orderRepository.findByOrderNoForUpdate(order.getOrderNo())).willReturn(Optional.of(order));

			// when & then
			assertThatThrownBy(() -> orderService.modifyOrder(1L, order.getOrderNo(), BigDecimal.valueOf(60000), 10))
				.isInstanceOf(BusinessException.class)
				.hasMessage(ErrorCode.ORDER_NOT_MODIFIABLE.getMessage());
		}

		@Test
		void 정정_실패_부분체결된_주문() {
			// given
			Order order = new Order(1L, 1L, OrderType.LIMIT, OrderSide.BUY, 10,
					BigDecimal.valueOf(50000), Currency.KRW, null,
					BigDecimal.valueOf(75), BigDecimal.ZERO);
			order.fillPartially(2);  // 부분체결 상태

			given(orderRepository.findByOrderNoForUpdate(order.getOrderNo()))
					.willReturn(Optional.of(order));

			// when & then
			assertThatThrownBy(() ->
					orderService.modifyOrder(1L, order.getOrderNo(), BigDecimal.valueOf(60000), 10))
					.isInstanceOf(BusinessException.class)
					.hasMessage(ErrorCode.ORDER_PARTIAL_NOT_MODIFIABLE.getMessage());
		}

		@Test
		void 정정_실패_주문없음() {
			// given
			given(orderRepository.findByOrderNoForUpdate(any())).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> orderService.modifyOrder(1L, UUID.randomUUID(), BigDecimal.valueOf(60000), 10))
				.isInstanceOf(BusinessException.class)
				.hasMessage(ErrorCode.ORDER_NOT_FOUND.getMessage());
		}
	}
}
