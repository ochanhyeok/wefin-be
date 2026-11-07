package com.solv.wefin.domain.news.tagging.service;

import com.solv.wefin.domain.news.article.entity.NewsArticle;
import com.solv.wefin.domain.news.article.entity.NewsArticle.RelevanceStatus;
import com.solv.wefin.domain.news.article.repository.NewsArticleRepository;
import com.solv.wefin.domain.news.config.NewsBatchProperties;
import com.solv.wefin.domain.news.tagging.client.OpenAiTaggingClient;
import com.solv.wefin.domain.news.tagging.dto.TaggingResult;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 기사 금융 관련성(RelevanceStatus)을 재판정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelevanceRejudgeService {

    private static final int REJUDGE_WINDOW_HOURS = 24; // cron 기반 PENDING 재판정 윈도우

    private final NewsArticleRepository newsArticleRepository;
    private final OpenAiTaggingClient openAiTaggingClient;
    private final RelevancePersistenceService persistenceService;
    private final NewsBatchProperties batchProperties;

    /**
     * 지정된 article ID들의 관련성을 재판정한다.
     *
     * @param articleIds 재판정 대상 기사 ID 목록
     * @return 재판정 처리 결과 요약
     */
    public RejudgeSummary rejudgeByIds(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return RejudgeSummary.empty();
        }

        List<NewsArticle> articles = newsArticleRepository.findAllById(articleIds);
        int requested = articleIds.size();
        int fetched = articles.size();
        log.info("관련성 재판정 시작(개별) — 요청: {}건, 조회: {}건", requested, fetched);

        RejudgeResult result = rejudgeArticles(articles);
        RejudgeSummary summary = new RejudgeSummary(
                requested, fetched, result.success(), result.skipped(), result.failed(),
                requested - fetched);
        log.info("관련성 재판정 완료(개별) — {}", summary);
        return summary;
    }

    /**
     * PENDING 상태의 기사를 배치 크기만큼 재판정한다.
     *
     * 노출 가능한 시간 윈도우({@link #REJUDGE_WINDOW_HOURS}시간) 안의 기사로 한정한다.
     *
     * @param limit 한 번에 처리할 최대 기사 수
     *              (허용 범위: 1 ~ {@code batch.news.rejudge-max-limit})
     * @return 재판정 처리 결과 요약
     */
    public RejudgeSummary rejudgePending(int limit) {
        validateLimit(limit);

        OffsetDateTime since = OffsetDateTime.now().minusHours(REJUDGE_WINDOW_HOURS);
        List<NewsArticle> articles = newsArticleRepository.findRejudgeTargets(
                RelevanceStatus.PENDING, since, PageRequest.of(0, limit));
        log.info("관련성 재판정 시작(PENDING) — 대상: {}건 (limit: {}, since: {})",
                articles.size(), limit, since);

        RejudgeResult result = rejudgeArticles(articles);
        RejudgeSummary summary = new RejudgeSummary(
                articles.size(), articles.size(), result.success(), result.skipped(), result.failed(), 0);
        log.info("관련성 재판정 완료(PENDING) — {}", summary);
        return summary;
    }

    private void validateLimit(int limit) {
        int maxLimit = batchProperties.rejudgeMaxLimit();
        if (limit < 1 || limit > maxLimit) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "limit는 1 이상 " + maxLimit + " 이하여야 합니다 (요청값: " + limit + ")");
        }
    }

    private RejudgeResult rejudgeArticles(List<NewsArticle> articles) {
        int successCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        for (NewsArticle article : articles) {
            try {
                if (article.getContent() == null || article.getContent().isBlank()) {
                    log.warn("관련성 재판정 스킵 — content 없음, articleId: {}", article.getId());
                    skippedCount++;
                    continue;
                }

                // 외부 API 호출은 트랜잭션 밖에서
                TaggingResult result = openAiTaggingClient.analyzeTags(article.getTitle(), article.getContent());
                RelevanceStatus relevance = RelevanceStatus.from(result.getRelevance());

                // 저장은 별도 트랜잭션으로 격리 (REQUIRES_NEW). 저장 실제 성공 여부를 boolean으로 받는다.
                boolean saved = persistenceService.saveRelevance(article.getId(), relevance);
                if (saved) {
                    successCount++;
                } else {
                    failedCount++;
                }
            } catch (Exception e) {
                // 스택트레이스 포함 로깅 (원인 추적 용이)
                log.warn("관련성 재판정 실패 — articleId: {}", article.getId(), e);
                failedCount++;
            }
        }
        return new RejudgeResult(successCount, skippedCount, failedCount);
    }

    private record RejudgeResult(int success, int skipped, int failed) {}

    /**
     * 재판정 처리 결과 요약.
     *
     * @param requested 요청 건수
     * @param fetched   실제 DB에서 조회된 건수
     * @param success   저장 성공 건수
     * @param skipped   content 없음 등으로 스킵된 건수
     * @param failed    AI 호출/저장 실패 건수
     * @param notFound  요청했으나 DB에 없는 기사 수 (rejudgeByIds에서만 non-zero)
     */
    public record RejudgeSummary(int requested, int fetched, int success,
                                 int skipped, int failed, int notFound) {
        public static RejudgeSummary empty() {
            return new RejudgeSummary(0, 0, 0, 0, 0, 0);
        }

        @Override
        public String toString() {
            return String.format("요청: %d, 조회: %d, 성공: %d, 스킵: %d, 실패: %d, 미존재: %d",
                    requested, fetched, success, skipped, failed, notFound);
        }
    }
}
