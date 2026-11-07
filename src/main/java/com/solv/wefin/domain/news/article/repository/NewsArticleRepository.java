package com.solv.wefin.domain.news.article.repository;

import com.solv.wefin.domain.news.article.entity.NewsArticle;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

    boolean existsByDedupKey(String dedupKey);

    /**
     * 출처 표시용 — 엔티티 전체가 아닌 publisherName + originalUrl만 조회한다.
     * content(TEXT) 등 불필요한 대용량 컬럼 로딩을 방지.
     */
    @Query("SELECT a.id AS id, a.publisherName AS publisherName, a.originalUrl AS originalUrl " +
            "FROM NewsArticle a WHERE a.id IN :ids")
    List<SourceProjection> findSourceInfoByIdIn(@Param("ids") List<Long> ids);

    interface SourceProjection {
        Long getId();
        String getPublisherName();
        String getOriginalUrl();
    }

    /**
     * 섹션 출처 카드용 — id, title, publisherName, originalUrl을 조회한다
     */
    @Query("SELECT a.id AS id, a.title AS title, a.publisherName AS publisherName, a.originalUrl AS originalUrl " +
            "FROM NewsArticle a WHERE a.id IN :ids")
    List<ArticleSourceProjection> findArticleSourceInfoByIdIn(@Param("ids") List<Long> ids);

    interface ArticleSourceProjection {
        Long getId();
        String getTitle();
        String getPublisherName();
        String getOriginalUrl();
    }

    /**
     * 재판정 가능한 PENDING 기사를 id 오름차순으로 조회한다.
     *
     * 클러스터링 단계가 24시간 윈도우(`ClusteringService.HOURS_RANGE`) 안의 기사만 받기 때문에,
     * 그 윈도우를 벗어난 PENDING은 재판정해 FINANCIAL로 바뀌어도 노출되지 않는다.
     * 따라서 cron 호출은 노출 가능한 시간 범위 안의 기사로 한정해 LLM 비용 누수를 막는다.
     * (admin 직접 호출 path인 {@code rejudgeByIds}는 이 제한을 받지 않는다)
     */
    @Query("SELECT a FROM NewsArticle a " +
            "WHERE a.relevance = :relevance " +
            "AND a.content IS NOT NULL AND TRIM(a.content) <> '' " +
            "AND a.createdAt > :since " +
            "ORDER BY a.id ASC")
    List<NewsArticle> findRejudgeTargets(
            @Param("relevance") NewsArticle.RelevanceStatus relevance,
            @Param("since") OffsetDateTime since,
            Pageable pageable);

    List<NewsArticle> findByCrawlStatusInAndCrawlRetryCountLessThanOrderByCollectedAtDesc(
            List<NewsArticle.CrawlStatus> statuses, int maxRetryCount, Pageable pageable);

    /**
     * 임베딩 대상 기사를 조회한다.
     *
     * 태깅이 완료되어 금융 관련성이 확정(FINANCIAL)된 기사만 후속 파이프라인에 진입시킨다.
     * PENDING(미판정) 기사를 임베딩하면 클러스터에 무관 기사가 섞일 수 있으므로 제외한다.
     */
    @Query("SELECT a FROM NewsArticle a " +
            "WHERE a.crawlStatus = :crawlStatus " +
            "AND a.relevance = :requiredRelevance " +
            "AND a.embeddingRetryCount < :maxRetryCount " +
            "AND (a.embeddingStatus IN :embeddingStatuses " +
            "     OR (a.embeddingStatus = :processingStatus AND a.embeddingAttemptedAt < :staleBefore)) " +
            "ORDER BY a.collectedAt DESC")
    List<NewsArticle> findEmbeddingTargets(
            @Param("crawlStatus") NewsArticle.CrawlStatus crawlStatus,
            @Param("embeddingStatuses") List<NewsArticle.EmbeddingStatus> embeddingStatuses,
            @Param("processingStatus") NewsArticle.EmbeddingStatus processingStatus,
            @Param("maxRetryCount") int maxRetryCount,
            @Param("staleBefore") OffsetDateTime staleBefore,
            @Param("requiredRelevance") NewsArticle.RelevanceStatus requiredRelevance,
            Pageable pageable);

    /**
     * 태깅 대상 기사를 조회한다.
     * PENDING/FAILED 상태이거나, PROCESSING 상태에서 staleBefore 이전에 시도된 기사를 포함한다.
     */
    @Query("SELECT a FROM NewsArticle a " +
            "WHERE a.crawlStatus = :crawlStatus " +
            "AND a.taggingRetryCount < :maxRetryCount " +
            "AND (a.taggingStatus IN :taggingStatuses " +
            "     OR (a.taggingStatus = :processingStatus AND a.taggingAttemptedAt < :staleBefore)) " +
            "ORDER BY a.collectedAt DESC")
    List<NewsArticle> findTaggingTargets(
            @Param("crawlStatus") NewsArticle.CrawlStatus crawlStatus,
            @Param("taggingStatuses") List<NewsArticle.TaggingStatus> taggingStatuses,
            @Param("processingStatus") NewsArticle.TaggingStatus processingStatus,
            @Param("maxRetryCount") int maxRetryCount,
            @Param("staleBefore") OffsetDateTime staleBefore,
            Pageable pageable);

    /**
     * 클러스터링 대상 기사를 조회한다.
     *
     * 금융 관련성이 확정(FINANCIAL)된 기사만 클러스터링한다.
     * PENDING(미판정) 기사가 섞이면 노출 시점에 무관 기사가 클러스터에 포함될 수 있으므로 제외한다.
     */
    @Query("SELECT a FROM NewsArticle a " +
            "WHERE a.embeddingStatus = :embeddingStatus " +
            "AND a.relevance = :requiredRelevance " +
            "AND NOT EXISTS (SELECT 1 FROM NewsClusterArticle nca WHERE nca.newsArticleId = a.id) " +
            "AND a.createdAt > :since " +
            "ORDER BY a.collectedAt DESC")
    List<NewsArticle> findClusteringTargets(
            @Param("embeddingStatus") NewsArticle.EmbeddingStatus embeddingStatus,
            @Param("since") OffsetDateTime since,
            @Param("requiredRelevance") NewsArticle.RelevanceStatus requiredRelevance,
            Pageable pageable);
}
