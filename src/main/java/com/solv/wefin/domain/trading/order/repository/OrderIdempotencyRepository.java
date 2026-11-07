package com.solv.wefin.domain.trading.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.solv.wefin.domain.trading.order.entity.OrderIdempotency;

@Repository
public interface OrderIdempotencyRepository extends JpaRepository<OrderIdempotency, String> {

	/**
	 * 멱등키 선점. PROCESSING 상태로 즉시 INSERT 한다.
	 *
	 * <p>같은 키가 이미 있으면 PK(UNIQUE) 위반 → {@code DataIntegrityViolationException}.
	 * 동시 따닥은 DB 가 유니크 인덱스에서 직렬화하므로 정확히 한 요청만 성공한다.</p>
	 */
	@Modifying
	@Query(value = "INSERT INTO order_idempotency (idempotency_key, status, created_at) "
			+ "VALUES (:key, 'PROCESSING', now())", nativeQuery = true)
	void insertClaim(@Param("key") String idempotencyKey);

	/** 주문 완료 후 결과(orderId) 기록 + DONE 전환. */
	@Modifying
	@Query(value = "UPDATE order_idempotency SET status = 'DONE', order_id = :orderId "
			+ "WHERE idempotency_key = :key", nativeQuery = true)
	void markDone(@Param("key") String idempotencyKey, @Param("orderId") Long orderId);
}
