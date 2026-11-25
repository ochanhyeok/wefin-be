package com.solv.wefin.domain.trading.order.entity;

import java.io.Serializable;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 멱등성 레코드. (계좌, 멱등키)를 PK 로 두어 같은 계좌의 동시 중복 주문을 DB 에서 직렬화한다.
 *
 * <p>선점 시 {@code PROCESSING} 으로 INSERT 하고, 주문 트랜잭션 안에서 {@code DONE} + 응답 스냅샷으로 갱신한다.
 * 비즈니스 거절(4xx)은 {@code FAILED} + 에러코드로 남기고, 시스템 오류(5xx)는 행을 지워 재시도를 허용한다.</p>
 */
@Entity
@Table(name = "order_idempotency")
@IdClass(OrderIdempotency.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderIdempotency {

	@Id
	@Column(name = "virtual_account_id")
	private Long virtualAccountId;

	@Id
	@Column(name = "idempotency_key", length = 64)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64)
	private String requestFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private IdempotencyStatus status;

	@Column(name = "order_id")
	private Long orderId;

	@Column(name = "error_code", length = 64)
	private String errorCode;

	@Column(name = "response_body", columnDefinition = "JSONB", insertable = false, updatable = false)
	private String responseBody;

	@Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
	private OffsetDateTime updatedAt;

	@Getter
	@EqualsAndHashCode
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class Key implements Serializable {
		private Long virtualAccountId;
		private String idempotencyKey;

		public Key(Long virtualAccountId, String idempotencyKey) {
			this.virtualAccountId = virtualAccountId;
			this.idempotencyKey = idempotencyKey;
		}
	}
}
