package com.solv.wefin.domain.trading.order.service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solv.wefin.domain.trading.order.dto.IdempotencyClaim;
import com.solv.wefin.domain.trading.order.dto.OrderInfo;
import com.solv.wefin.domain.trading.order.dto.OrderReplaySnapshot;
import com.solv.wefin.domain.trading.order.entity.OrderIdempotency;
import com.solv.wefin.domain.trading.order.repository.OrderIdempotencyRepository;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 멱등 레코드의 생명주기(선점 → 완료/거절/해제). 선점·거절·해제는 각자 짧은 트랜잭션으로 바로 커밋하고,
 * 완료만 주문 트랜잭션에 합류해 주문과 원자적으로 기록된다.
 */
@Service
@RequiredArgsConstructor
public class OrderIdempotencyService {

	/**
	 * PROCESSING 이 이 시간 넘게 갱신되지 않으면 처리하던 서버가 죽은 것으로 보고 재선점을 허용한다.
	 * 주문 트랜잭션 타임아웃({@link OrderService#ORDER_TX_TIMEOUT_SECONDS})보다 충분히 길어야
	 * 살아 있는 요청을 가로채 이중 주문이 나지 않는다.
	 */
	public static final Duration PROCESSING_LEASE = Duration.ofSeconds(60);

	/** 선점 실패 직후 기록을 읽었는데 그 사이 지워진 경우(다른 요청의 해제) 다시 선점을 시도하는 횟수 */
	private static final int CLAIM_ATTEMPTS = 3;

	private final OrderIdempotencyRepository repository;
	private final ObjectMapper objectMapper;

	/**
	 * 키를 선점한다. 성공하면 이번 선점의 토큰을, 이미 기록이 있으면 그 기록을 돌려준다.
	 *
	 * <p>선점이 0 건인데 이어서 읽은 기록이 없을 수 있다. 앞 요청이 5xx 로 해제했거나 정리 배치가 지운 경우다.
	 * 이때 "기록 없음"을 선점 성공으로 보면 기록 없이 주문하게 되므로, 몇 번 다시 선점해 보고 그래도 안 되면 409 로 돌려보낸다.</p>
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ClaimResult claim(Long virtualAccountId, String key, String fingerprint) {
		for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
			UUID token = UUID.randomUUID();
			if (repository.claim(virtualAccountId, key, fingerprint, token, PROCESSING_LEASE.toSeconds()) == 1) {
				return ClaimResult.claimed(new IdempotencyClaim(virtualAccountId, key, token));
			}
			Optional<OrderIdempotency> existing = repository.find(virtualAccountId, key);
			if (existing.isPresent()) {
				return ClaimResult.existing(existing.get());
			}
		}
		throw new BusinessException(ErrorCode.ORDER_REQUEST_IN_PROGRESS);
	}

	/**
	 * 주문 트랜잭션 안에서 호출한다(REQUIRED). 주문이 롤백되면 이 기록도 함께 롤백돼 PROCESSING 으로 남는다.
	 *
	 * <p>갱신이 0 건이면 이 요청의 선점이 아니다. 리스가 끝나 다른 요청이 재선점했거나 행이 지워졌다.
	 * 그대로 커밋하면 주문은 남고 기록은 다른 요청 것이 되어 이중 주문이 되므로, 예외로 주문 트랜잭션을 롤백한다.
	 * 같은 키의 다른 요청이 처리 중이므로 409 로 응답해 클라이언트가 잠시 뒤 같은 키로 결과를 받게 한다.</p>
	 */
	@Transactional
	public void markDone(IdempotencyClaim claim, OrderInfo info) {
		int updated = repository.markDone(claim.virtualAccountId(), claim.idempotencyKey(), claim.token(),
			info.order().getOrderId(), toJson(OrderReplaySnapshot.from(info)));
		if (updated != 1) {
			throw new BusinessException(ErrorCode.ORDER_REQUEST_IN_PROGRESS);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(IdempotencyClaim claim, ErrorCode errorCode) {
		repository.markFailed(claim.virtualAccountId(), claim.idempotencyKey(), claim.token(), errorCode.name());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void release(IdempotencyClaim claim) {
		repository.release(claim.virtualAccountId(), claim.idempotencyKey(), claim.token());
	}

	public OrderReplaySnapshot readSnapshot(OrderIdempotency record) {
		try {
			return objectMapper.readValue(record.getResponseBody(), OrderReplaySnapshot.class);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("멱등 응답 스냅샷을 읽을 수 없습니다. key=" + record.getIdempotencyKey(), e);
		}
	}

	private String toJson(OrderReplaySnapshot snapshot) {
		try {
			return objectMapper.writeValueAsString(snapshot);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("멱등 응답 스냅샷을 만들 수 없습니다.", e);
		}
	}

	/** 선점 결과: 새로 선점했으면 {@code claim}, 이미 기록이 있으면 {@code existing} 중 하나만 있다. */
	public record ClaimResult(IdempotencyClaim claim, OrderIdempotency existing) {
		static ClaimResult claimed(IdempotencyClaim claim) {
			return new ClaimResult(claim, null);
		}

		static ClaimResult existing(OrderIdempotency record) {
			return new ClaimResult(null, record);
		}

		public boolean isClaimed() {
			return claim != null;
		}
	}
}
