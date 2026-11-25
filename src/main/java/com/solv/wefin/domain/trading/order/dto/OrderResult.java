package com.solv.wefin.domain.trading.order.dto;

/**
 * 멱등 주문 결과. {@code replayed} 면 이번 요청으로 주문이 새로 생기지 않았고, 첫 요청의 결과를 다시 돌려준 것이다.
 */
public record OrderResult(OrderInfo info, boolean replayed) {

	public static OrderResult created(OrderInfo info) {
		return new OrderResult(info, false);
	}

	public static OrderResult replayed(OrderInfo info) {
		return new OrderResult(info, true);
	}
}
