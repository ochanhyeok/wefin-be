package com.solv.wefin.domain.trading.order.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.solv.wefin.domain.trading.order.repository.OrderIdempotencyRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 보존 기간(24시간)이 지난 멱등 기록을 정리한다.
 *
 * <p>삭제는 몇 번을 다시 돌려도 결과가 같아서, 여러 인스턴스가 동시에 돌거나 중간에 죽어도 분산 락 없이 안전하다.
 * 한 번에 지우면 대량 행 락과 WAL 폭증이 생기므로 {@link #BATCH_SIZE} 건씩 각자 트랜잭션으로 나눠 지운다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderIdempotencyCleanupScheduler {

	static final int RETENTION_HOURS = 24;
	static final int BATCH_SIZE = 1000;
	/** 한 번 실행에서 지우는 상한. 밀린 양이 많아도 한 실행이 끝없이 돌지 않게 하고, 남은 건 다음 실행에 넘긴다. */
	static final int MAX_BATCHES_PER_RUN = 100;

	private final OrderIdempotencyRepository repository;
	private final TransactionTemplate transactionTemplate;

	@Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
	public void cleanUp() {
		int total = purgeExpired();
		if (total > 0) {
			log.info("[멱등 기록 정리] 삭제 {}건", total);
		}
	}

	public int purgeExpired() {
		int total = 0;
		for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
			Integer deleted = transactionTemplate.execute(status -> repository.deleteExpired(RETENTION_HOURS, BATCH_SIZE));
			int count = deleted == null ? 0 : deleted;
			total += count;
			if (count < BATCH_SIZE) {
				break;
			}
		}
		return total;
	}
}
