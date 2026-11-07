package com.solv.wefin.domain.news.cluster.service;

import com.solv.wefin.domain.news.article.entity.NewsArticle;
import com.solv.wefin.domain.news.article.repository.NewsArticleRepository;
import com.solv.wefin.domain.news.cluster.entity.NewsCluster;
import com.solv.wefin.domain.news.cluster.entity.NewsCluster.ClusterStatus;
import com.solv.wefin.domain.news.cluster.repository.NewsClusterRepository;
import com.solv.wefin.domain.news.config.NewsBatchProperties;
import com.solv.wefin.domain.news.cluster.service.ClusterMatchingService.MatchResult;
import com.solv.wefin.domain.news.cluster.service.SuspiciousScoringService.ScoreResult;
import com.solv.wefin.domain.news.cluster.service.SuspiciousScoringService.Verdict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 클러스터링 전체 흐름을 관리하는 서비스
 *
 * 임베딩 완료된 미배정 기사를 조회하여, 기존 클러스터에 추가하거나
 * 새 단독 클러스터를 생성한다. 외부 계산(유사도, scoring)은 트랜잭션 밖에서 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusteringService {

    private static final int HOURS_RANGE = 24;

    private final NewsArticleRepository newsArticleRepository;
    private final NewsClusterRepository newsClusterRepository;
    private final ArticleVectorService articleVectorService;
    private final ClusterMatchingService clusterMatchingService;
    private final SuspiciousScoringService suspiciousScoringService;
    private final ClusteringPersistenceService persistenceService;
    private final NewsBatchProperties batchProperties;

    /**
     * 임베딩이 완료된 기사 중 아직 클러스터에 속하지 않은 기사를 가져온다.
     */
    public void clusterPendingArticles() {
        clusterMatchingService.clearCache();

        List<NewsArticle> targets = findClusteringTargets();
        log.info("클러스터링 대상 기사 수: {}", targets.size());

        if (targets.isEmpty()) {
            return;
        }

        List<NewsCluster> activeClusters = newsClusterRepository.findByStatus(ClusterStatus.ACTIVE);

        int matchedCount = 0;
        int createdCount = 0;
        int createdByRejectionCount = 0;
        int suspiciousCount = 0;
        int skippedCount = 0;

        // 각 기사별로 클러스터 매칭 수행
        for (NewsArticle article : targets) {
            try {
                ClusterResult result = processArticle(article, activeClusters);

                switch (result) {
                    case MATCHED -> matchedCount++;
                    case SUSPICIOUS_MATCHED -> {
                        matchedCount++;
                        suspiciousCount++;
                    }
                    case CREATED -> createdCount++;
                    case CREATED_BY_REJECTION -> createdByRejectionCount++;
                    case SKIPPED_NO_VECTOR -> skippedCount++;
                }
            } catch (Exception e) {
                log.warn("클러스터링 실패 - articleId: {}, error: {}", article.getId(), e.getMessage());
            }
        }

        ClusterStats stats = getClusterStats(activeClusters);
        log.info("클러스터링 완료 - 매칭: {}, 신규: {}, scoring거부→신규: {}, suspicious: {}, 스킵(벡터없음): {}, 총 클러스터: {}, 평균 size: {}",
                matchedCount, createdCount, createdByRejectionCount, suspiciousCount, skippedCount,
                stats.totalClusters(), String.format("%.1f", stats.avgSize()));
    }

    private ClusterResult processArticle(NewsArticle article, List<NewsCluster> activeClusters) {
        // 1. 기사 대표 벡터 계산
        float[] articleVector = articleVectorService.calculateRepresentativeVector(article.getId());
        if (articleVector == null) {
            log.warn("임베딩이 없는 기사 → 클러스터링 스킵 - articleId: {}", article.getId());
            return ClusterResult.SKIPPED_NO_VECTOR;
        }

        // 2. centroid 매칭 + 상위 K건 샘플링 검증
        Optional<MatchResult> match = clusterMatchingService.findBestMatch(articleVector, activeClusters);

        if (match.isEmpty()) {
            NewsCluster newCluster = persistenceService.createSingleCluster(article, articleVector);
            activeClusters.add(newCluster);
            return ClusterResult.CREATED;
        }

        NewsCluster cluster = match.get().cluster();

        // 3. 태그 soft scoring (임베딩은 비슷하지만 실제 의미가 다른 경우 걸러낸다.)
        ScoreResult scoreResult = suspiciousScoringService.score(article.getId(), cluster.getId());

        if (scoreResult.verdict() == Verdict.REJECT) {
            log.debug("태그 scoring 거부 → 신규 클러스터 생성 - articleId: {}, clusterId: {}, score: {}",
                    article.getId(), cluster.getId(), scoreResult.score());
            NewsCluster newCluster = persistenceService.createSingleCluster(article, articleVector);
            activeClusters.add(newCluster);
            return ClusterResult.CREATED_BY_REJECTION;
        }

        boolean suspicious = scoreResult.verdict() == Verdict.SUSPICIOUS;
        persistenceService.addToCluster(cluster, article, articleVector, suspicious);

        return suspicious ? ClusterResult.SUSPICIOUS_MATCHED : ClusterResult.MATCHED;
    }

    private List<NewsArticle> findClusteringTargets() {
        OffsetDateTime since = OffsetDateTime.now().minusHours(HOURS_RANGE);
        return newsArticleRepository.findClusteringTargets(
                NewsArticle.EmbeddingStatus.SUCCESS,
                since,
                NewsArticle.RelevanceStatus.FINANCIAL,
                PageRequest.of(0, batchProperties.clusteringSize()));
    }

    private ClusterStats getClusterStats(List<NewsCluster> clusters) {
        int totalClusters = clusters.size();
        double avgSize = clusters.isEmpty() ? 0.0 :
                clusters.stream().mapToInt(NewsCluster::getArticleCount).average().orElse(0.0);
        return new ClusterStats(totalClusters, avgSize);
    }

    /**
     * MATCHED: 기존 클러스터에 정상 추가
     * SUSPICIOUS_MATCHED: 기존 클러스터에 추가 + suspicious 플래그
     * CREATED: 매칭 실패로 새 단독 클러스터 생성
     * CREATED_BY_REJECTION: 태그 scoring 거부로 새 단독 클러스터 생성 (매칭은 됐으나 태그 불일치)
     * SKIPPED_NO_VECTOR: 임베딩이 없어서 클러스터링 스킵
     */
    private enum ClusterResult {
        MATCHED, SUSPICIOUS_MATCHED, CREATED, CREATED_BY_REJECTION, SKIPPED_NO_VECTOR
    }

    private record ClusterStats(int totalClusters, double avgSize) {
    }
}
