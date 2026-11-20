package com.solv.wefin.domain.trading.order.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 멱등성 레코드. 클라이언트 멱등키(idempotencyKey)를 PK 로 두어 동시 중복 주문을 DB UNIQUE 로 차단한다.
 *
 * <p>트랜잭션 시작 시 {@code PROCESSING} 으로 선점 INSERT(중복이면 위반으로 차단)하고,
 * 주문이 끝나면 {@code DONE} + orderId 로 갱신한다. (토스페이먼츠 Idempotency-Key 패턴)</p>
 */
@Entity
@Table(name = "order_idempotency")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderIdempotency {

	@Id
	@Column(name = "idempotency_key", length = 64)
	private String idempotencyKey;

	@Column(nullable = false, length = 20)
	private String status;

	@Column(name = "order_id")
	private Long orderId;

	@Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
	private OffsetDateTime createdAt;
}
