package com.solv.wefin.domain.trading.reconciliation.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.solv.wefin.domain.trading.reconciliation.entity.ReconciliationMismatch;
import com.solv.wefin.domain.trading.reconciliation.entity.ReconciliationRun;
import com.solv.wefin.domain.trading.reconciliation.repository.ReconciliationQueryRepository;
import com.solv.wefin.domain.trading.reconciliation.repository.ReconciliationQueryRepository.Row;
import com.solv.wefin.domain.trading.reconciliation.repository.ReconciliationRunRepository;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 원장 대사. 실행을 세 단계로 나눠 각자 트랜잭션으로 커밋한다.
 *
 * <ol>
 *   <li>시작: RUNNING 기록 INSERT. 동시에 두 번째 실행이 들어오면 유니크 인덱스에 막힌다(분산 락 대신 DB 제약)</li>
 *   <li>검사: 계좌 대사, 주문 대사 → 불일치 저장</li>
 *   <li>종료: SUCCEEDED + 건수, 또는 실패 시 FAILED + 원인</li>
 * </ol>
 * 실패 기록이 검사 트랜잭션과 함께 롤백되면 "실패했다는 사실"이 사라지므로 단계를 나눴다.
 * 대사는 읽고 기록만 하므로 같은 시점에 몇 번을 다시 돌려도 결과가 같다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

	/** 이 시간 넘게 RUNNING 이면 실행하던 서버가 죽은 것으로 본다 */
	static final int RUN_TIMEOUT_MINUTES = 30;

	private final ReconciliationRunRepository runRepository;
	private final ReconciliationQueryRepository queryRepository;
	private final TransactionTemplate transactionTemplate;

	public ReconciliationRun run(ReconciliationRun.Trigger trigger) {
		ReconciliationRun run = start(trigger);
		try {
			ReconciliationRun finished = transactionTemplate.execute(status -> check(run.getRunId()));
			if (finished.hasMismatch()) {
				log.error("[대사 불일치] runId={} 계좌 {}건, 주문 {}건", finished.getRunId(),
					finished.getAccountMismatches(), finished.getOrderMismatches());
			} else {
				log.info("[대사 완료] runId={} 계좌 {}개, 주문 {}건 모두 일치", finished.getRunId(),
					finished.getCheckedAccounts(), finished.getCheckedOrders());
			}
			return finished;
		} catch (RuntimeException e) {
			markFailed(run.getRunId(), e);
			throw e;
		}
	}

	public List<ReconciliationRun> recentRuns(int size) {
		return runRepository.findRecent(PageRequest.of(0, size));
	}

	private ReconciliationRun start(ReconciliationRun.Trigger trigger) {
		try {
			return transactionTemplate.execute(status -> {
				runRepository.failAbandoned(RUN_TIMEOUT_MINUTES);
				return runRepository.saveAndFlush(ReconciliationRun.start(trigger));
			});
		} catch (DataIntegrityViolationException e) {
			throw new BusinessException(ErrorCode.RECONCILIATION_ALREADY_RUNNING);
		}
	}

	private ReconciliationRun check(Long runId) {
		List<ReconciliationMismatch> mismatches = new ArrayList<>();

		int checkedAccounts = queryRepository.countAccounts();
		List<Row> accountRows = queryRepository.findAccountMismatches();
		accountRows.forEach(r -> mismatches.add(toMismatch(runId, ReconciliationMismatch.Type.ACCOUNT, r)));

		int checkedOrders = queryRepository.countClosedOrders();
		List<Row> orderRows = queryRepository.findOrderMismatches();
		orderRows.forEach(r -> mismatches.add(toMismatch(runId, ReconciliationMismatch.Type.ORDER, r)));

		queryRepository.saveAll(mismatches);

		ReconciliationRun run = runRepository.findByIdForUpdate(runId).orElseThrow();
		if (!run.isRunning()) {
			// 제한 시간을 넘겨 다른 실행이 FAILED 로 정리했다. 성공으로 덮지 않고, 저장한 불일치도 함께 롤백한다
			throw new IllegalStateException("제한 시간을 넘겨 중단 처리된 실행입니다. runId=" + runId);
		}
		run.succeed(checkedAccounts, accountRows.size(), checkedOrders, orderRows.size());
		return run;
	}

	private void markFailed(Long runId, RuntimeException cause) {
		try {
			transactionTemplate.executeWithoutResult(status ->
				runRepository.findByIdForUpdate(runId)
					.filter(ReconciliationRun::isRunning)   // 이미 FAILED 로 정리된 실행의 원인을 덮어쓰지 않는다
					.ifPresent(run -> run.fail(summarize(cause))));
		} catch (RuntimeException e) {
			// 실패 기록조차 못 남기면 RUNNING 으로 남고, 다음 실행이 제한 시간 뒤 FAILED 로 정리한다
			log.error("[대사] 실패 기록 실패 runId={}", runId, e);
		}
		log.error("[대사 실패] runId={}", runId, cause);
	}

	private static ReconciliationMismatch toMismatch(Long runId, ReconciliationMismatch.Type type, Row r) {
		return new ReconciliationMismatch(runId, type, r.getAccountId(), r.getOrderId(), r.getExpected(), r.getActual());
	}

	private static String summarize(Throwable e) {
		String message = e.getClass().getSimpleName() + ": " + e.getMessage();
		return message.length() > 1000 ? message.substring(0, 1000) : message;
	}
}
