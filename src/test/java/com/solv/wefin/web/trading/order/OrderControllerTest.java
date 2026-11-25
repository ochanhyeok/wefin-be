package com.solv.wefin.web.trading.order;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.solv.wefin.domain.trading.order.dto.OrderCancelInfo;
import com.solv.wefin.domain.trading.stock.entity.Stock;
import com.solv.wefin.domain.trading.stock.service.StockService;
import com.solv.wefin.global.config.security.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.solv.wefin.domain.trading.account.entity.VirtualAccount;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.order.dto.OrderInfo;
import com.solv.wefin.domain.trading.order.dto.OrderResult;
import com.solv.wefin.domain.trading.order.entity.Order;
import com.solv.wefin.domain.trading.order.entity.OrderSide;
import com.solv.wefin.domain.trading.order.entity.OrderStatus;
import com.solv.wefin.domain.trading.order.entity.OrderType;
import com.solv.wefin.domain.trading.order.service.MarketOrderFacade;
import com.solv.wefin.domain.trading.order.service.OrderService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrderService orderService;
	@MockitoBean
	private MarketOrderFacade marketOrderFacade;
	@MockitoBean
	private VirtualAccountService accountService;
	@MockitoBean
	private JwtProvider jwtProvider;
	@MockitoBean
	private StockService stockService;

	private VirtualAccount mockAccount;
	private UUID testUserId;

	@BeforeEach
	void setUp() {
		testUserId = UUID.randomUUID();
		mockAccount = mock(VirtualAccount.class);
		given(mockAccount.getVirtualAccountId()).willReturn(1L);
		given(accountService.getAccountByUserId(any())).willReturn(mockAccount);
	}

	@Test
	void 매수_성공() throws Exception {
		// given
		Order mockOrder = createMockOrder(OrderSide.BUY, OrderType.MARKET, OrderStatus.FILLED);

		OrderInfo mockOrderInfo = new OrderInfo(mockOrder, "005930", "삼성전자",
			new BigDecimal("178000"), new BigDecimal("9024854"),
			BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("9024854"));

		Stock stock = mockStock(1L, "005930", "삼성전자");
		given(stockService.findByStockCode("005930")).willReturn(Optional.of(stock));
		String key = UUID.randomUUID().toString();
		given(marketOrderFacade.buyMarket(1L, 1L, 10, key))
			.willReturn(OrderResult.created(mockOrderInfo));

		// when & then
		mockMvc.perform(post("/api/order/buy")
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"stockCode\": \"005930\", \"quantity\": 10}")
				.with(csrf())
				.with(authentication(
					new UsernamePasswordAuthenticationToken(testUserId, null, List.of())
				)))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist("Idempotent-Replayed"))
			.andExpect(jsonPath("$.data.side").value("BUY"))
			.andExpect(jsonPath("$.data.stockCode").value("005930"))
			.andExpect(jsonPath("$.data.quantity").value(10));
	}

	@Test
	void 매수_재전송이면_헤더로_알린다() throws Exception {
		// given
		Order mockOrder = createMockOrder(OrderSide.BUY, OrderType.MARKET, OrderStatus.FILLED);
		OrderInfo mockOrderInfo = new OrderInfo(mockOrder, "005930", "삼성전자",
			new BigDecimal("178000"), new BigDecimal("9024854"),
			BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("9024854"));
		Stock stock = mockStock(1L, "005930", "삼성전자");
		given(stockService.findByStockCode("005930")).willReturn(Optional.of(stock));
		String key = UUID.randomUUID().toString();
		given(marketOrderFacade.buyMarket(1L, 1L, 10, key))
			.willReturn(OrderResult.replayed(mockOrderInfo));

		// when & then
		mockMvc.perform(post("/api/order/buy")
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"stockCode\": \"005930\", \"quantity\": 10}")
				.with(csrf())
				.with(authentication(
					new UsernamePasswordAuthenticationToken(testUserId, null, List.of())
				)))
			.andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andExpect(jsonPath("$.data.quantity").value(10));
	}

	@Test
	void 매수_같은키_처리중이면_409() throws Exception {
		// given
		Stock stock = mockStock(1L, "005930", "삼성전자");
		given(stockService.findByStockCode("005930")).willReturn(Optional.of(stock));
		String key = UUID.randomUUID().toString();
		given(marketOrderFacade.buyMarket(1L, 1L, 10, key))
			.willThrow(new BusinessException(ErrorCode.ORDER_REQUEST_IN_PROGRESS));

		// when & then
		mockMvc.perform(post("/api/order/buy")
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"stockCode\": \"005930\", \"quantity\": 10}")
				.with(csrf())
				.with(authentication(
					new UsernamePasswordAuthenticationToken(testUserId, null, List.of())
				)))
			.andExpect(status().isConflict());
	}

	@Test
	void 매도_성공() throws Exception {
		// given
		Order mockOrder = createMockOrder(OrderSide.SELL, OrderType.MARKET, OrderStatus.FILLED);

		OrderInfo mockOrderInfo = new OrderInfo(mockOrder, "005930", "삼성전자",
			new BigDecimal("178000"), new BigDecimal("9013931"),
			new BigDecimal("3204"), new BigDecimal("80000"), new BigDecimal("9024582"));

		Stock stock = mockStock(1L, "005930", "삼성전자");
		given(stockService.findByStockCode("005930")).willReturn(Optional.of(stock));
		given(marketOrderFacade.sellMarket(anyLong(), anyLong(), anyInt()))
			.willReturn(mockOrderInfo);

		// when & then
		mockMvc.perform(post("/api/order/sell")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"stockCode\": \"005930\", \"quantity\": 10}")
				.with(csrf())
				.with(authentication(
					new UsernamePasswordAuthenticationToken(testUserId, null, List.of())
				)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.side").value("SELL"))
			.andExpect(jsonPath("$.data.tax").value(3204))
			.andExpect(jsonPath("$.data.realizedProfit").value(80000))
			.andExpect(jsonPath("$.data.stockCode").value("005930"))
			.andExpect(jsonPath("$.data.quantity").value(10));
	}

	@Test
	void 정정_성공() throws Exception {
		// given
		Order mockOrder = createMockOrder(OrderSide.BUY, OrderType.LIMIT, OrderStatus.PENDING);

		OrderInfo mockOrderInfo = new OrderInfo(mockOrder, "005930", "삼성전자",
			BigDecimal.valueOf(50000), BigDecimal.valueOf(600000), BigDecimal.ZERO,
			BigDecimal.ZERO, BigDecimal.valueOf(1000000));
		given(orderService.modifyOrder(anyLong(), any(UUID.class), any(BigDecimal.class), anyInt()))
			.willReturn(mockOrderInfo);

		// when & then
		mockMvc.perform(put("/api/order/{orderNo}", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"requestPrice\": 60000, \"quantity\": 10}")
				.with(csrf())
				.with(authentication(
					new UsernamePasswordAuthenticationToken(testUserId, null, List.of())
				)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.stockCode").value("005930"));
	}

	@Test
	void 취소_성공() throws Exception {
		// given
		Order mockOrder = createMockOrder(OrderSide.BUY, OrderType.LIMIT, OrderStatus.CANCELLED);

		OrderCancelInfo mockCancelInfo = new OrderCancelInfo(mockOrder, BigDecimal.valueOf(500075),
			BigDecimal.valueOf(5000000));
		given(orderService.cancelOrder(anyLong(), any(UUID.class)))
			.willReturn(mockCancelInfo);

		// when & then
		mockMvc.perform(delete("/api/order/{orderNo}", UUID.randomUUID())
				.with(csrf())
				.with(authentication(
					new UsernamePasswordAuthenticationToken(testUserId, null, List.of())
				)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("CANCELLED"));
	}

	@Test
	void 미체결_조회_성공() throws Exception {
		// given
		Order mockOrder = createMockOrder(OrderSide.BUY, OrderType.LIMIT, OrderStatus.PENDING);
		given(mockOrder.getOrderId()).willReturn(1L);
		given(mockOrder.getStockId()).willReturn(100L);
		given(mockOrder.getRequestPrice()).willReturn(new BigDecimal("50000"));

		Stock stock = mockStock(100L, "005930", "삼성전자");

		given(orderService.findPendingOrders(anyLong())).willReturn(List.of(mockOrder));
		given(stockService.findAllByIdIn(anyList())).willReturn(List.of(stock));

		// when & then
		mockMvc.perform(get("/api/order/pending")
				.with(authentication(
					new UsernamePasswordAuthenticationToken(testUserId, null, List.of())
				)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].status").value("PENDING"))
			.andExpect(jsonPath("$.data[0].stockCode").value("005930"));
	}

	@Test
	void 주문내역_커서_조회_성공() throws Exception {
		// given
		Order mockOrder = createMockOrder(OrderSide.BUY, OrderType.MARKET, OrderStatus.FILLED);
		given(mockOrder.getOrderId()).willReturn(1L);
		given(mockOrder.getStockId()).willReturn(100L);
		given(mockOrder.getRequestPrice()).willReturn(null);

		Stock stock = mockStock(100L, "005930", "삼성전자");

		given(orderService.searchOrders(anyLong(), any(), any(), anyInt()))
			.willReturn(List.of(mockOrder));
		given(stockService.findAllByIdIn(anyList())).willReturn(List.of(stock));

		// when & then
		mockMvc.perform(get("/api/order/history")
				.param("size", "20")
				.with(authentication(
					new UsernamePasswordAuthenticationToken(testUserId, null, List.of())
				)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isArray())
			.andExpect(jsonPath("$.data.content[0].stockCode").value("005930"))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	void 오늘_체결_조회_성공() throws Exception {
		// given
		Order mockOrder = createMockOrder(OrderSide.BUY, OrderType.MARKET, OrderStatus.FILLED);
		given(mockOrder.getOrderId()).willReturn(1L);
		given(mockOrder.getStockId()).willReturn(100L);
		given(mockOrder.getRequestPrice()).willReturn(null);

		Stock stock = mockStock(100L, "005930", "삼성전자");

		given(orderService.findTodayFilledOrders(anyLong())).willReturn(List.of(mockOrder));
		given(stockService.findAllByIdIn(anyList())).willReturn(List.of(stock));

		// when & then
		mockMvc.perform(get("/api/order/today")
				.with(authentication(
					new UsernamePasswordAuthenticationToken(testUserId, null, List.of())
				)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].status").value("FILLED"))
			.andExpect(jsonPath("$.data[0].stockCode").value("005930"));

	}

	private Order createMockOrder(OrderSide side, OrderType type, OrderStatus status) {
		Order mockOrder = mock(Order.class);
		given(mockOrder.getOrderNo()).willReturn(UUID.randomUUID());
		given(mockOrder.getSide()).willReturn(side);
		given(mockOrder.getOrderType()).willReturn(type);
		given(mockOrder.getQuantity()).willReturn(10);
		given(mockOrder.getStatus()).willReturn(status);
		given(mockOrder.getFee()).willReturn(new BigDecimal("146"));
		given(mockOrder.getTax()).willReturn(BigDecimal.ZERO);
		given(mockOrder.getCreatedAt()).willReturn(OffsetDateTime.now());
		return mockOrder;
	}

	private Stock mockStock(Long stockId, String stockCode, String stockName) {
		Stock mockStock = mock(Stock.class);
		given(mockStock.getId()).willReturn(stockId);
		given(mockStock.getStockCode()).willReturn(stockCode);
		given(mockStock.getStockName()).willReturn(stockName);
		return mockStock;
	}
}