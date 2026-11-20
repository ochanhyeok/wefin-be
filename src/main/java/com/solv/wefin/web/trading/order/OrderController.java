package com.solv.wefin.web.trading.order;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.solv.wefin.domain.trading.account.entity.VirtualAccount;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.order.dto.OrderCancelInfo;
import com.solv.wefin.domain.trading.order.dto.OrderInfo;
import com.solv.wefin.domain.trading.order.dto.OrderSearchCondition;
import com.solv.wefin.domain.trading.order.entity.Order;
import com.solv.wefin.domain.trading.order.entity.OrderStatus;
import com.solv.wefin.domain.trading.order.service.OrderService;
import com.solv.wefin.domain.trading.stock.entity.Stock;
import com.solv.wefin.domain.trading.stock.service.StockService;
import com.solv.wefin.global.common.ApiResponse;
import com.solv.wefin.global.common.CursorResponse;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import com.solv.wefin.web.trading.order.dto.request.OrderBuyRequest;
import com.solv.wefin.web.trading.order.dto.request.OrderModifyRequest;
import com.solv.wefin.web.trading.order.dto.request.OrderSellRequest;
import com.solv.wefin.web.trading.order.dto.response.OrderCancelResponse;
import com.solv.wefin.web.trading.order.dto.response.OrderHistoryResponse;
import com.solv.wefin.web.trading.order.dto.response.OrderResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

	private final OrderService orderService;
	private final VirtualAccountService accountService;
	private final StockService stockService;

	@PostMapping("/buy")
	public ApiResponse<OrderResponse> buy(@AuthenticationPrincipal UUID userId,
										  @Valid @RequestBody OrderBuyRequest request,
										  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		VirtualAccount account = accountService.getAccountByUserId(userId);
		Stock stock = stockService.findByStockCode(request.stockCode().strip())
			.orElseThrow(() -> new BusinessException(ErrorCode.MARKET_STOCK_NOT_FOUND));
		String key = (idempotencyKey != null && !idempotencyKey.isBlank())
			? idempotencyKey : UUID.randomUUID().toString();
		OrderInfo orderInfo = orderService.buyMarket(account.getVirtualAccountId(),
			stock.getId(), request.quantity(), key);
		OrderResponse response = OrderResponse.from(orderInfo);
		return ApiResponse.success(response);
	}

	@PostMapping("/sell")
	public ApiResponse<OrderResponse> sell(@AuthenticationPrincipal UUID userId,
										   @Valid @RequestBody OrderSellRequest request) {
		VirtualAccount account = accountService.getAccountByUserId(userId);
		Stock stock = stockService.findByStockCode(request.stockCode().strip())
			.orElseThrow(() -> new BusinessException(ErrorCode.MARKET_STOCK_NOT_FOUND));
		OrderInfo orderInfo = orderService.sellMarket(account.getVirtualAccountId(),
			stock.getId(), request.quantity());
		OrderResponse response = OrderResponse.from(orderInfo);
		return ApiResponse.success(response);
	}

	@PutMapping("/{orderNo}")
	public ApiResponse<OrderResponse> modify(@AuthenticationPrincipal UUID userId,
											 @PathVariable UUID orderNo,
											 @Valid @RequestBody OrderModifyRequest request) {
		VirtualAccount account = accountService.getAccountByUserId(userId);
		OrderInfo info = orderService.modifyOrder(account.getVirtualAccountId(), orderNo,
			request.requestPrice(), request.quantity());
		OrderResponse response = OrderResponse.from(info);
		return ApiResponse.success(response);
	}

	@DeleteMapping("/{orderNo}")
	public ApiResponse<OrderCancelResponse> cancel(@AuthenticationPrincipal UUID userId,
												   @PathVariable UUID orderNo) {
		VirtualAccount account = accountService.getAccountByUserId(userId);
		OrderCancelInfo cancelInfo = orderService.cancelOrder(account.getVirtualAccountId(), orderNo);
		OrderCancelResponse response = OrderCancelResponse.from(cancelInfo);
		return ApiResponse.success(response);
	}

	@GetMapping("/pending")
	public ApiResponse<List<OrderHistoryResponse>> getPendingOrders(@AuthenticationPrincipal UUID userId) {
		VirtualAccount account = accountService.getAccountByUserId(userId);
		List<Order> orders = orderService.findPendingOrders(account.getVirtualAccountId());
		return ApiResponse.success(toOrderHistoryResponse(orders));
	}

	@GetMapping("/today")
	public ApiResponse<List<OrderHistoryResponse>> getTodayOrders(@AuthenticationPrincipal UUID userId) {
		VirtualAccount account = accountService.getAccountByUserId(userId);
		List<Order> orders = orderService.findTodayFilledOrders(account.getVirtualAccountId());
		return ApiResponse.success(toOrderHistoryResponse(orders));
	}

	@GetMapping("/history")
	public ApiResponse<CursorResponse<OrderHistoryResponse>> getOrderHistory(
			@AuthenticationPrincipal UUID userId,
			@RequestParam(required = false) List<OrderStatus> status,
			@RequestParam(required = false) String stockCode,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
			@RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

		if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
			throw new BusinessException(ErrorCode.MARKET_INVALID_DATE);
		}

		VirtualAccount account = accountService.getAccountByUserId(userId);

		Long stockId = null;
		if (stockCode != null && !stockCode.isBlank()) {
			Stock stock = stockService.findByStockCode(stockCode.strip()).orElse(null);
			if (stock == null) {
				return ApiResponse.success(CursorResponse.empty());
			}
			stockId = stock.getId();
		}

		OrderSearchCondition condition = new OrderSearchCondition(status, stockId, startDate, endDate);
		List<Order> orders = orderService.searchOrders(account.getVirtualAccountId(), condition, cursor, size);

		Map<Long, Stock> stockMap = buildStockMap(orders);

		return ApiResponse.success(CursorResponse.from(
			orders, size,
			order -> {
				Stock stock = stockMap.get(order.getStockId());
				return OrderHistoryResponse.from(order,
					stock != null ? stock.getStockCode() : null,
					stock != null ? stock.getStockName() : null);
			},
			Order::getOrderId
		));
	}

	private List<OrderHistoryResponse> toOrderHistoryResponse(List<Order> orders) {
		Map<Long, Stock> stockMap = buildStockMap(orders);

		return orders.stream()
			.map(order -> {
				Stock stock = stockMap.get(order.getStockId());
				return OrderHistoryResponse.from(order,
					stock != null ? stock.getStockCode() : null,
					stock != null ? stock.getStockName() : null);
			})
			.toList();
	}

	private Map<Long, Stock> buildStockMap(List<Order> orders) {
		List<Long> stockIds = orders.stream()
			.map(Order::getStockId)
			.distinct()
			.toList();

		return stockService.findAllByIdIn(stockIds).stream()
			.collect(Collectors.toMap(Stock::getId, Function.identity()));
	}
}
