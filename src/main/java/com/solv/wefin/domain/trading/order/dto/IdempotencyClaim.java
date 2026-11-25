package com.solv.wefin.domain.trading.order.dto;

import java.util.UUID;

/**
 * 멱등키 선점 결과. {@code token} 은 이번 선점에만 발급된 펜싱 토큰이다.
 * 완료·거절·해제는 이 토큰이 행의 토큰과 같을 때만 반영돼, 리스가 끝나 다른 요청에게 넘어간 선점을 건드리지 못한다.
 */
public record IdempotencyClaim(Long virtualAccountId, String idempotencyKey, UUID token) {
}
