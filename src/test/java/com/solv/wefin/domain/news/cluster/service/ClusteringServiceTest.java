package com.solv.wefin.domain.news.cluster.service;

import com.solv.wefin.domain.news.article.entity.NewsArticle;
import com.solv.wefin.domain.news.article.repository.NewsArticleRepository;
import com.solv.wefin.domain.news.cluster.entity.NewsCluster;
import com.solv.wefin.domain.news.cluster.entity.NewsCluster.ClusterStatus;
import com.solv.wefin.domain.news.cluster.repository.NewsClusterRepository;
import com.solv.wefin.domain.news.cluster.service.ClusterMatchingService.MatchResult;
import com.solv.wefin.domain.news.cluster.service.SuspiciousScoringService.ScoreResult;
import com.solv.wefin.domain.news.cluster.service.SuspiciousScoringService.Verdict;
import com.solv.wefin.domain.news.config.NewsBatchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClusteringServiceTest {

    private ClusteringService clusteringService;

    @Mock
    private NewsArticleRepository newsArticleRepository;

    @Mock
    private NewsClusterRepository newsClusterRepository;

    @Mock
    private ArticleVectorService articleVectorService;

    @Mock
    private ClusterMatchingService clusterMatchingService;

    @Mock
    private SuspiciousScoringService suspiciousScoringService;

    @Mock
    private ClusteringPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        clusteringService = new ClusteringService(
                newsArticleRepository, newsClusterRepository, articleVectorService,
                clusterMatchingService, suspiciousScoringService, persistenceService,
                new NewsBatchProperties(500, 500, 500, 500, 50, 500, 50));
    }

    private NewsArticle createArticle(Long id) {
        NewsArticle article = NewsArticle.builder()
                .rawNewsArticleId(1L)
                .publisherName("테스트")
                .title("테스트 기사 " + id)
                .originalUrl("https://example.com/" + id)
                .build();
        ReflectionTestUtils.setField(article, "id", id);
        ReflectionTestUtils.setField(article, "embeddingStatus", NewsArticle.EmbeddingStatus.SUCCESS);
        return article;
    }

    @Test
    @DisplayName("매칭 성공 시 기존 클러스터에 추가한다")
    void clusterPendingArticles_matched() {
        // given
        NewsArticle article = createArticle(1L);
        float[] vector = {1.0f, 0.0f, 0.0f};

        NewsCluster cluster = NewsCluster.createSingle(vector, 99L, null, OffsetDateTime.now());
        ReflectionTestUtils.setField(cluster, "id", 10L);

        given(newsArticleRepository.findClusteringTargets(any(), any(), any(), any()))
                .willReturn(List.of(article));
        given(newsClusterRepository.findByStatus(ClusterStatus.ACTIVE))
                .willReturn(new ArrayList<>(List.of(cluster)));
        given(articleVectorService.calculateRepresentativeVector(1L))
                .willReturn(vector);
        given(clusterMatchingService.findBestMatch(any(), any()))
                .willReturn(Optional.of(new MatchResult(cluster, 0.95)));
        given(suspiciousScoringService.score(1L, 10L))
                .willReturn(new ScoreResult(100, Verdict.NORMAL));

        // when
        clusteringService.clusterPendingArticles();

        // then
        verify(clusterMatchingService).clearCache();
        verify(persistenceService).addToCluster(cluster, article, vector, false);
        verify(persistenceService, never()).createSingleCluster(any(), any());
    }

    @Test
    @DisplayName("매칭 실패 시 새 단독 클러스터를 생성한다")
    void clusterPendingArticles_created() {
        // given
        NewsArticle article = createArticle(1L);
        float[] vector = {1.0f, 0.0f, 0.0f};

        NewsCluster newCluster = NewsCluster.createSingle(vector, 1L, null, OffsetDateTime.now());
        ReflectionTestUtils.setField(newCluster, "id", 20L);

        given(newsArticleRepository.findClusteringTargets(any(), any(), any(), any()))
                .willReturn(List.of(article));
        given(newsClusterRepository.findByStatus(ClusterStatus.ACTIVE))
                .willReturn(new ArrayList<>());
        given(articleVectorService.calculateRepresentativeVector(1L))
                .willReturn(vector);
        given(clusterMatchingService.findBestMatch(any(), any()))
                .willReturn(Optional.empty());
        given(persistenceService.createSingleCluster(article, vector))
                .willReturn(newCluster);

        // when
        clusteringService.clusterPendingArticles();

        // then
        verify(persistenceService).createSingleCluster(article, vector);
        verify(persistenceService, never()).addToCluster(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("태그 scoring REJECT 시 새 단독 클러스터를 생성한다")
    void clusterPendingArticles_rejectedByScoring() {
        // given
        NewsArticle article = createArticle(1L);
        float[] vector = {1.0f, 0.0f, 0.0f};

        NewsCluster cluster = NewsCluster.createSingle(vector, 99L, null, OffsetDateTime.now());
        ReflectionTestUtils.setField(cluster, "id", 10L);

        NewsCluster newCluster = NewsCluster.createSingle(vector, 1L, null, OffsetDateTime.now());
        ReflectionTestUtils.setField(newCluster, "id", 20L);

        given(newsArticleRepository.findClusteringTargets(any(), any(), any(), any()))
                .willReturn(List.of(article));
        given(newsClusterRepository.findByStatus(ClusterStatus.ACTIVE))
                .willReturn(new ArrayList<>(List.of(cluster)));
        given(articleVectorService.calculateRepresentativeVector(1L))
                .willReturn(vector);
        given(clusterMatchingService.findBestMatch(any(), any()))
                .willReturn(Optional.of(new MatchResult(cluster, 0.85)));
        given(suspiciousScoringService.score(1L, 10L))
                .willReturn(new ScoreResult(60, Verdict.REJECT));
        given(persistenceService.createSingleCluster(article, vector))
                .willReturn(newCluster);

        // when
        clusteringService.clusterPendingArticles();

        // then
        verify(persistenceService).createSingleCluster(article, vector);
        verify(persistenceService, never()).addToCluster(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("태그 scoring SUSPICIOUS 시 suspicious 플래그와 함께 추가한다")
    void clusterPendingArticles_suspicious() {
        // given
        NewsArticle article = createArticle(1L);
        float[] vector = {1.0f, 0.0f, 0.0f};

        NewsCluster cluster = NewsCluster.createSingle(vector, 99L, null, OffsetDateTime.now());
        ReflectionTestUtils.setField(cluster, "id", 10L);

        given(newsArticleRepository.findClusteringTargets(any(), any(), any(), any()))
                .willReturn(List.of(article));
        given(newsClusterRepository.findByStatus(ClusterStatus.ACTIVE))
                .willReturn(new ArrayList<>(List.of(cluster)));
        given(articleVectorService.calculateRepresentativeVector(1L))
                .willReturn(vector);
        given(clusterMatchingService.findBestMatch(any(), any()))
                .willReturn(Optional.of(new MatchResult(cluster, 0.85)));
        given(suspiciousScoringService.score(1L, 10L))
                .willReturn(new ScoreResult(75, Verdict.SUSPICIOUS));

        // when
        clusteringService.clusterPendingArticles();

        // then
        verify(persistenceService).addToCluster(cluster, article, vector, true);
    }

    @Test
    @DisplayName("벡터가 없는 기사는 SKIPPED 처리한다")
    void clusterPendingArticles_skippedNoVector() {
        // given
        NewsArticle article = createArticle(1L);

        given(newsArticleRepository.findClusteringTargets(any(), any(), any(), any()))
                .willReturn(List.of(article));
        given(newsClusterRepository.findByStatus(ClusterStatus.ACTIVE))
                .willReturn(new ArrayList<>());
        given(articleVectorService.calculateRepresentativeVector(1L))
                .willReturn(null);

        // when
        clusteringService.clusterPendingArticles();

        // then
        verify(persistenceService, never()).addToCluster(any(), any(), any(), anyBoolean());
        verify(persistenceService, never()).createSingleCluster(any(), any());
    }

    @Test
    @DisplayName("대상 기사가 없으면 아무것도 실행하지 않는다")
    void clusterPendingArticles_noTargets() {
        // given
        given(newsArticleRepository.findClusteringTargets(any(), any(), any(), any()))
                .willReturn(List.of());

        // when
        clusteringService.clusterPendingArticles();

        // then
        verify(newsClusterRepository, never()).findByStatus(any());
        verify(persistenceService, never()).addToCluster(any(), any(), any(), anyBoolean());
        verify(persistenceService, never()).createSingleCluster(any(), any());
    }
}
