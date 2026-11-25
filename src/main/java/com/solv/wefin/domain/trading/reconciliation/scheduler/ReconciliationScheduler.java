package com.solv.wefin.domain.trading.reconciliation.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.solv.wefin.domain.trading.reconciliation.entity.ReconciliationRun;
import com.solv.wefin.domain.trading.reconciliation.service.ReconciliationService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {

	private final ReconciliationService reconciliationService;

	/** 장 마감(15:30), 일일 스냅샷(15:40) 이후. 실패하면 관리자 API 로 다시 돌린다. */
	@Scheduled(cron = "0 10 16 * * MON-FRI", zone = "Asia/Seoul")
	public void reconcile() {
		try {
			reconciliationService.run(ReconciliationRun.Trigger.SCHEDULED);
		} catch (BusinessException e) {
			if (e.getErrorCode() == ErrorCode.RECONCILIATION_ALREADY_RUNNING) {
				log.info("[대사] 다른 인스턴스가 실행 중이라 건너뜀");
				return;
			}
			throw e;
		} catch (RuntimeException e) {
			// 실패는 ReconciliationService 가 FAILED 로 기록했다. 스케줄러 스레드까지 죽이지 않는다.
		}
	}
}
