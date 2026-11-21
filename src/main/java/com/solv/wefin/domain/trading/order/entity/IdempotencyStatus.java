package com.solv.wefin.domain.trading.order.entity;

public enum IdempotencyStatus {
	/** 선점됨 — 주문 처리 중 */
	PROCESSING,
	/** 주문 성공 — 응답 스냅샷을 재전송한다 */
	DONE,
	/** 비즈니스 거절(잔고 부족 등) — 같은 거절을 다시 돌려준다 */
	FAILED
}
