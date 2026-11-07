package com.solv.wefin.domain.news.tagging.batch;

import com.solv.wefin.domain.news.config.NewsBatchProperties;
import com.solv.wefin.domain.news.tagging.service.RelevanceRejudgeService;
import com.solv.wefin.domain.news.tagging.service.RelevanceRejudgeService.RejudgeSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PENDING 상태로 남은 기사의 금융 관련성을 정기적으로 재판정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelevanceRejudgeScheduler {

    private final RelevanceRejudgeService relevanceRejudgeService;
    private final NewsBatchProperties batchProperties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${rejudge.collect.cron:-}")
    public void rejudgePending() {
        if (!running.compareAndSet(false, true)) {
            log.info("관련성 재판정이 이미 실행 중입니다. 스킵합니다.");
            return;
        }

        log.info("=== 관련성 재판정 배치 시작 ===");
        long start = System.currentTimeMillis();

        try {
            RejudgeSummary summary = relevanceRejudgeService.rejudgePending(batchProperties.rejudgeBatchSize());
            log.info("관련성 재판정 배치 결과 — {}", summary);
        } catch (Exception e) {
            log.error("관련성 재판정 배치 실패: {}", e.getMessage(), e);
        } finally {
            running.set(false);
            long elapsed = System.currentTimeMillis() - start;
            log.info("=== 관련성 재판정 배치 종료 ({}ms) ===", elapsed);
        }
    }
}
