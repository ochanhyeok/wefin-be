package com.solv.wefin.domain.trading.order.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.solv.wefin.domain.trading.order.entity.OrderIdempotency;

@Repository
public interface OrderIdempotencyRepository extends JpaRepository<OrderIdempotency, OrderIdempotency.Key> {

	/**
	 * 멱등키 선점. 처음 보는 키면 PROCESSING 으로 INSERT 하고 1 을 반환한다.
	 *
	 * <p>이미 있으면 예외 대신 0 을 반환한다(ON CONFLICT). 예외로 받으면 PostgreSQL 트랜잭션이 aborted 상태가 돼
	 * 같은 트랜잭션에서 기존 기록을 조회할 수 없다. 단, 같은 지문의 PROCESSING 이
	 * 리스 시간({@code leaseSeconds}) 넘게 멈춰 있으면(처리하던 서버가 죽음) 다시 선점하고 1 을 반환한다.
	 * 경과 시간은 DB 시계(now())로 잰다. 앱 서버마다 시계가 어긋나도 판정이 흔들리지 않는다.</p>
	 *
	 * <p>선점할 때마다(재선점 포함) 새 {@code token} 을 기록한다. 이후 완료·거절·해제는 이 토큰이 일치할 때만 반영된다.</p>
	 */
	@Modifying
	@Query(value = """
			INSERT INTO order_idempotency
			    (virtual_account_id, idempotency_key, request_fingerprint, status, claim_token, created_at, updated_at)
			VALUES (:accountId, :key, :fingerprint, 'PROCESSING', :token, now(), now())
			ON CONFLICT (virtual_account_id, idempotency_key) DO UPDATE
			    SET updated_at = now(), claim_token = EXCLUDED.claim_token
			    WHERE order_idempotency.status = 'PROCESSING'
			      AND order_idempotency.request_fingerprint = EXCLUDED.request_fingerprint
			      AND order_idempotency.updated_at < now() - make_interval(secs => :leaseSeconds)
			""", nativeQuery = true)
	int claim(@Param("accountId") Long virtualAccountId, @Param("key") String idempotencyKey,
		@Param("fingerprint") String fingerprint, @Param("token") UUID token, @Param("leaseSeconds") long leaseSeconds);

	@Query("SELECT i FROM OrderIdempotency i WHERE i.virtualAccountId = :accountId AND i.idempotencyKey = :key")
	Optional<OrderIdempotency> find(@Param("accountId") Long virtualAccountId, @Param("key") String idempotencyKey);

	/**
	 * 주문 성공 — 주문 트랜잭션 안에서 호출해 주문과 함께 커밋/롤백된다.
	 * 0 이면 이 요청의 선점이 아니다(리스 만료 후 재선점됨, 또는 행이 지워짐). 호출자는 주문을 롤백해야 한다.
	 */
	@Modifying
	@Query(value = """
			UPDATE order_idempotency
			SET status = 'DONE', order_id = :orderId, response_body = CAST(:body AS JSONB), updated_at = now()
			WHERE virtual_account_id = :accountId AND idempotency_key = :key AND status = 'PROCESSING'
			  AND claim_token = :token
			""", nativeQuery = true)
	int markDone(@Param("accountId") Long virtualAccountId, @Param("key") String idempotencyKey,
		@Param("token") UUID token, @Param("orderId") Long orderId, @Param("body") String responseBody);

	/** 비즈니스 거절 — 재요청에도 같은 거절을 돌려주기 위해 에러코드를 남긴다. */
	@Modifying
	@Query(value = """
			UPDATE order_idempotency
			SET status = 'FAILED', error_code = :errorCode, updated_at = now()
			WHERE virtual_account_id = :accountId AND idempotency_key = :key AND status = 'PROCESSING'
			  AND claim_token = :token
			""", nativeQuery = true)
	int markFailed(@Param("accountId") Long virtualAccountId, @Param("key") String idempotencyKey,
		@Param("token") UUID token, @Param("errorCode") String errorCode);

	/** 시스템 오류 — 주문이 일어나지 않았으므로 선점을 풀어 같은 키로 재시도할 수 있게 한다. */
	@Modifying
	@Query(value = """
			DELETE FROM order_idempotency
			WHERE virtual_account_id = :accountId AND idempotency_key = :key AND status = 'PROCESSING'
			  AND claim_token = :token
			""", nativeQuery = true)
	int release(@Param("accountId") Long virtualAccountId, @Param("key") String idempotencyKey,
		@Param("token") UUID token);

	/**
	 * 보존 기간이 지난 기록을 최대 {@code limit} 건 삭제한다. 한 번에 지우면 긴 락·WAL 폭증이 생겨 나눠 지운다.
	 * 생성은 오래됐어도 리스 안에서 처리 중인 PROCESSING(재선점된 행)은 지우지 않는다.
	 */
	@Modifying
	@Query(value = """
			DELETE FROM order_idempotency
			WHERE ctid IN (
			    SELECT ctid FROM order_idempotency
			    WHERE created_at < now() - make_interval(hours => :retentionHours)
			      AND NOT (status = 'PROCESSING' AND updated_at >= now() - make_interval(secs => :leaseSeconds))
			    LIMIT :limit
			)
			""", nativeQuery = true)
	int deleteExpired(@Param("retentionHours") int retentionHours, @Param("leaseSeconds") long leaseSeconds,
		@Param("limit") int limit);
}
