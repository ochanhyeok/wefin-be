package com.solv.wefin.domain.trading.order.dto;

import java.math.BigDecimal;

import com.solv.wefin.domain.trading.order.entity.Order;

/**
 * 재전송용 응답 스냅샷. 주문 자체는 order_id 로 다시 읽고, 주문 시점에만 알 수 있는 값(체결가, 직후 잔고 등)을 저장한다.
 * 잔고를 지금 다시 조회하면 그 사이 다른 주문이 반영돼 첫 응답과 달라진다.
 */
public record OrderReplaySnapshot(
	String stockCode,
	String stockName,
	BigDecimal price,
	BigDecimal totalAmount,
	BigDecimal tax,
	BigDecimal realizedProfit,
	BigDecimal balance
) {
	public static OrderReplaySnapshot from(OrderInfo info) {
		return new OrderReplaySnapshot(info.stockCode(), info.stockName(), info.price(), info.totalAmount(),
			info.tax(), info.realizedProfit(), info.balance());
	}

	public OrderInfo toOrderInfo(Order order) {
		return new OrderInfo(order, stockCode, stockName, price, totalAmount, tax, realizedProfit, balance);
	}
}
