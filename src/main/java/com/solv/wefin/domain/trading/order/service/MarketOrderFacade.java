package com.solv.wefin.domain.trading.order.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.solv.wefin.domain.trading.common.MarketPriceProvider;
import com.solv.wefin.domain.trading.common.StockInfoProvider;
import com.solv.wefin.domain.trading.order.dto.OrderInfo;
import com.solv.wefin.domain.trading.order.dto.OrderResult;
import com.solv.wefin.domain.trading.order.entity.Order;
import com.solv.wefin.domain.trading.order.entity.OrderIdempotency;
import com.solv.wefin.domain.trading.order.repository.OrderRepository;
import com.solv.wefin.domain.trading.stock.entity.Stock;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 시장가 주문 진입점. 트랜잭션을 직접 열지 않고 선점 → 시세 조회 → 주문 → 결과 기록을 각자 트랜잭션으로 엮는다.
 *
 * <p>선점을 주문 트랜잭션 밖에서 먼저 커밋해야 동시에 들어온 같은 키 요청이 그 기록을 보고 멈춘다.
 * 주문이 실패하면 주문 트랜잭션은 롤백되지만 선점 기록은 남아 있으므로, 실패 종류에 따라 여기서 정리한다.</p>
 *
 * <ul>
 *   <li>처음 보는 키 → 주문 실행, 성공하면 주문과 함께 DONE 기록</li>
 *   <li>같은 키·같은 내용·DONE → 첫 응답 재전송</li>
 *   <li>같은 키·같은 내용·FAILED → 첫 거절 재전송</li>
 *   <li>같은 키·같은 내용·PROCESSING → 409 (기다리게 하면 스레드·커넥션을 붙잡는다)</li>
 *   <li>같은 키·다른 내용 → 422</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketOrderFacade {

	private final OrderService orderService;
	private final OrderIdempotencyService idempotencyService;
	private final OrderRepository orderRepository;
	private final StockInfoProvider stockInfoProvider;
	private final MarketPriceProvider marketPriceProvider;

	public OrderResult buyMarket(Long virtualAccountId, Long stockId, Integer quantity, String idempotencyKey) {
		// 값싼 입력 검증을 먼저: 잘못된 요청이 선점 기록을 남기면 고친 요청이 같은 키로 막힌다
		validateKey(idempotencyKey);
		if (quantity == null || quantity <= 0) {
			throw new BusinessException(ErrorCode.ORDER_INVALID_QUANTITY);
		}

		String fingerprint = fingerprint("BUY", "MARKET", stockId, quantity);
		Optional<OrderIdempotency> existing = idempotencyService.claim(virtualAccountId, idempotencyKey, fingerprint);
		if (existing.isPresent()) {
			return replay(existing.get(), fingerprint);
		}

		try {
			BigDecimal currentPrice = quote(stockId);
			return OrderResult.created(
				orderService.buyMarket(virtualAccountId, stockId, quantity, currentPrice, idempotencyKey));
		} catch (BusinessException e) {
			settleFailure(virtualAccountId, idempotencyKey, e);
			throw e;
		} catch (RuntimeException e) {
			releaseQuietly(virtualAccountId, idempotencyKey);
			throw e;
		}
	}

	/** 시장가 매도. 멱등 처리는 아직 매수에만 적용돼 있다 (ADR 0001 남은 일). */
	public OrderInfo sellMarket(Long virtualAccountId, Long stockId, Integer quantity) {
		if (quantity == null || quantity <= 0) {
			throw new BusinessException(ErrorCode.ORDER_INVALID_QUANTITY);
		}
		return orderService.sellMarket(virtualAccountId, stockId, quantity, quote(stockId));
	}

	/**
	 * 현재가 조회. 주문 트랜잭션을 열기 전에 호출한다.
	 * 트랜잭션 안에서 부르면 한투가 느릴 때 그 시간만큼 DB 커넥션을 쥐고 기다려, 주문과 무관한 요청까지 커넥션을 못 얻는다.
	 */
	private BigDecimal quote(Long stockId) {
		Stock stock = stockInfoProvider.getStock(stockId);
		return marketPriceProvider.getCurrentPrice(stock.getStockCode());
	}

	private OrderResult replay(OrderIdempotency record, String fingerprint) {
		if (!record.getRequestFingerprint().equals(fingerprint)) {
			throw new BusinessException(ErrorCode.ORDER_IDEMPOTENCY_KEY_REUSED);
		}
		return switch (record.getStatus()) {
			case PROCESSING -> throw new BusinessException(ErrorCode.ORDER_REQUEST_IN_PROGRESS);
			case FAILED -> throw new BusinessException(ErrorCode.valueOf(record.getErrorCode()));
			case DONE -> {
				Order order = orderRepository.findById(record.getOrderId())
					.orElseThrow(() -> new IllegalStateException("멱등 기록의 주문이 없습니다. orderId=" + record.getOrderId()));
				OrderInfo info = idempotencyService.readSnapshot(record).toOrderInfo(order);
				yield OrderResult.replayed(info);
			}
		};
	}

	/**
	 * 4xx(잔고 부족 등)는 같은 요청을 다시 보내도 결과가 같은 확정 거절이라 기록한다.
	 * 5xx(시세 API 장애 등)는 일시적 오류라 선점을 풀어 같은 키로 재시도하게 한다.
	 */
	private void settleFailure(Long virtualAccountId, String key, BusinessException e) {
		if (e.getErrorCode().getStatus() >= 500) {
			releaseQuietly(virtualAccountId, key);
			return;
		}
		try {
			idempotencyService.markFailed(virtualAccountId, key, e.getErrorCode());
		} catch (RuntimeException recordFailure) {
			// 기록에 실패해도 원래 거절은 그대로 돌려준다. 기록은 PROCESSING 으로 남았다가 리스가 끝나면 재선점된다.
			log.warn("멱등 거절 기록 실패 accountId={} key={}", virtualAccountId, key, recordFailure);
		}
	}

	private void releaseQuietly(Long virtualAccountId, String key) {
		try {
			idempotencyService.release(virtualAccountId, key);
		} catch (RuntimeException releaseFailure) {
			// DB 장애로 해제도 실패하면 리스가 끝난 뒤 재선점된다(그 사이 재시도는 409)
			log.warn("멱등 선점 해제 실패 accountId={} key={}", virtualAccountId, key, releaseFailure);
		}
	}

	private static void validateKey(String key) {
		if (key == null || key.isBlank()) {
			throw new BusinessException(ErrorCode.ORDER_IDEMPOTENCY_KEY_REQUIRED);
		}
		try {
			UUID.fromString(key);
		} catch (IllegalArgumentException e) {
			throw new BusinessException(ErrorCode.ORDER_IDEMPOTENCY_KEY_INVALID);
		}
		if (key.length() != 36) {   // UUID.fromString 은 "1-1-1-1-1" 같은 짧은 형태도 받아준다
			throw new BusinessException(ErrorCode.ORDER_IDEMPOTENCY_KEY_INVALID);
		}
	}

	static String fingerprint(Object... parts) {
		StringBuilder canonical = new StringBuilder();
		for (Object part : parts) {
			canonical.append(part).append('|');
		}
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
