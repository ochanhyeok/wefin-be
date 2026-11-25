package com.solv.wefin.domain.trading.order.service;

import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solv.wefin.domain.trading.order.dto.OrderInfo;
import com.solv.wefin.domain.trading.order.dto.OrderReplaySnapshot;
import com.solv.wefin.domain.trading.order.entity.OrderIdempotency;
import com.solv.wefin.domain.trading.order.repository.OrderIdempotencyRepository;
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
	static final Duration PROCESSING_LEASE = Duration.ofSeconds(60);

	private final OrderIdempotencyRepository repository;
	private final ObjectMapper objectMapper;

	/** @return 선점에 성공하면 empty, 이미 기록이 있으면 그 기록 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<OrderIdempotency> claim(Long virtualAccountId, String key, String fingerprint) {
		if (repository.claim(virtualAccountId, key, fingerprint, PROCESSING_LEASE.toSeconds()) == 1) {
			return Optional.empty();
		}
		return repository.find(virtualAccountId, key);
	}

	/** 주문 트랜잭션 안에서 호출한다(REQUIRED). 주문이 롤백되면 이 기록도 함께 롤백돼 PROCESSING 으로 남는다. */
	@Transactional
	public void markDone(Long virtualAccountId, String key, OrderInfo info) {
		repository.markDone(virtualAccountId, key, info.order().getOrderId(), toJson(OrderReplaySnapshot.from(info)));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(Long virtualAccountId, String key, ErrorCode errorCode) {
		repository.markFailed(virtualAccountId, key, errorCode.name());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void release(Long virtualAccountId, String key) {
		repository.release(virtualAccountId, key);
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
}
