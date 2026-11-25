package com.solv.wefin.domain.trading.market.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * 한투 시세 API 서킷 브레이커.
 *
 * <p>한투가 죽었을 때 모든 요청이 타임아웃(2초)까지 기다렸다 실패하면, 그동안 요청 스레드가 묶이고 한투에는 재시도가 몰린다.
 * 최근 호출의 절반 이상이 실패하거나 느리면 회로를 열어 10초 동안 호출 없이 바로 실패시키고,
 * 그 뒤 3건만 시험 삼아 보내 회복 여부를 본다.</p>
 */
@Slf4j
@Configuration
public class HantuCircuitBreakerConfig {

	@Bean
	public CircuitBreaker hantuPriceCircuitBreaker() {
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
			.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
			.slidingWindowSize(10)
			.minimumNumberOfCalls(5)                       // 호출이 적을 때 한두 건 실패로 열리지 않게
			.failureRateThreshold(50)
			.slowCallDurationThreshold(Duration.ofMillis(1500))
			.slowCallRateThreshold(50)                     // 실패는 아니어도 느린 호출이 절반이면 연다
			.waitDurationInOpenState(Duration.ofSeconds(10))
			.permittedNumberOfCallsInHalfOpenState(3)
			.build();
		CircuitBreaker circuitBreaker = CircuitBreaker.of("hantu-price", config);
		circuitBreaker.getEventPublisher().onStateTransition(event ->
			log.warn("[한투 서킷 브레이커] {}", event.getStateTransition()));
		return circuitBreaker;
	}
}
