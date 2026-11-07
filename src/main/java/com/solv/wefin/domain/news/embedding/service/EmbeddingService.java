package com.solv.wefin.domain.news.embedding.service;

import com.solv.wefin.domain.news.embedding.chunk.ArticleChunker;
import com.solv.wefin.domain.news.embedding.client.OpenAiEmbeddingClient;
import com.solv.wefin.domain.news.embedding.entity.ArticleEmbedding;
import com.solv.wefin.domain.news.article.entity.NewsArticle;
import com.solv.wefin.domain.news.article.entity.NewsArticle.CrawlStatus;
import com.solv.wefin.domain.news.article.entity.NewsArticle.EmbeddingStatus;
import com.solv.wefin.domain.news.article.entity.NewsArticle.RelevanceStatus;
import com.solv.wefin.domain.news.article.repository.NewsArticleRepository;
import com.solv.wefin.domain.news.config.NewsBatchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 임베딩 생성 전체 흐름을 관리하는 서비스
 *
 * 외부 API 호출은 트랜잭션 밖에서 수행하고,
 * DB 저장은 EmbeddingPersistenceService에서 트랜잭션으로 처리한다.
 * 20건 단위로 묶어서 배치 트랜잭션을 실행하고, 실패 건은 개별 fallback으로 격리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private static final int PROCESS_CHUNK_SIZE = 20;
    private static final int MAX_RETRY = 3;
    private static final int STALE_PROCESSING_MINUTES = 30;

    private final NewsArticleRepository newsArticleRepository;
    private final OpenAiEmbeddingClient openAiEmbeddingClient;
    private final ArticleChunker articleChunker;
    private final EmbeddingPersistenceService persistenceService;
    private final NewsBatchProperties batchProperties;

    @Value("${openai.embedding.model}")
    private String embeddingModel;

    @Value("${openai.embedding.version:v1}")
    private String embeddingVersion;

    /**
     * 크롤링 완료 + 임베딩 미생성 기사를 조회하여 임베딩을 생성한다.
     *
     * 500건씩 조회한 뒤 20건 단위로 묶어 처리한다.
     * 기사별로 청크 분할 → OpenAI API 호출 → DB 저장 순서로 진행하며,
     * 개별 기사 실패는 다른 기사에 영향을 주지 않도록 격리한다.
     */
    public void generatePendingEmbeddings() {
        List<NewsArticle> targets = findEmbeddingTargets();
        log.info("임베딩 대상 기사 수: {}", targets.size());

        if (targets.isEmpty()) {
            return;
        }

        persistenceService.markProcessing(targets);

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < targets.size(); i += PROCESS_CHUNK_SIZE) {
            List<NewsArticle> chunk = targets.subList(i, Math.min(i + PROCESS_CHUNK_SIZE, targets.size()));
            int[] result = processChunk(chunk);
            successCount += result[0];
            failCount += result[1];
        }

        log.info("임베딩 생성 완료 - 성공: {}, 실패: {}", successCount, failCount);
    }

    private List<NewsArticle> findEmbeddingTargets() {
        OffsetDateTime staleBefore = OffsetDateTime.now().minusMinutes(STALE_PROCESSING_MINUTES);
        return newsArticleRepository.findEmbeddingTargets(
                CrawlStatus.SUCCESS,
                List.of(EmbeddingStatus.PENDING, EmbeddingStatus.FAILED),
                EmbeddingStatus.PROCESSING,
                MAX_RETRY,
                staleBefore,
                RelevanceStatus.FINANCIAL,
                PageRequest.of(0, batchProperties.embeddingSize()));
    }

    private int[] processChunk(List<NewsArticle> articles) {
        List<ArticleEmbedding> allEmbeddings = new ArrayList<>();
        List<NewsArticle> successArticles = new ArrayList<>();
        int failCount = 0;

        for (NewsArticle article : articles) {
            try {
                List<ArticleEmbedding> embeddings = generateEmbeddingsForArticle(article);
                allEmbeddings.addAll(embeddings);
                successArticles.add(article);
            } catch (Exception e) {
                log.warn("임베딩 생성 실패 - articleId: {}, error: {}", article.getId(), e.getMessage());
                persistenceService.markFailed(article.getId(), e.getMessage());
                failCount++;
            }
        }

        if (!allEmbeddings.isEmpty()) {
            try {
                persistenceService.saveEmbeddingsBatch(allEmbeddings, successArticles);
            } catch (Exception e) {
                log.error("임베딩 배치 저장 실패, 개별 fallback 시도: {}", e.getMessage());
                failCount += handleBatchSaveFailure(successArticles, e.getMessage());
                return new int[]{0, failCount};
            }
        }

        return new int[]{successArticles.size(), failCount};
    }

    private List<ArticleEmbedding> generateEmbeddingsForArticle(NewsArticle article) {
        List<ArticleChunker.Chunk> chunks = articleChunker.chunk(article.getTitle(), article.getContent());

        if (chunks.isEmpty()) {
            throw new IllegalStateException("청크 분할 결과가 비어있습니다");
        }

        List<String> chunkTexts = chunks.stream()
                .map(ArticleChunker.Chunk::getChunkText)
                .toList();

        List<float[]> vectors = openAiEmbeddingClient.getEmbeddings(chunkTexts);

        List<ArticleEmbedding> embeddings = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ArticleChunker.Chunk chunk = chunks.get(i);
            embeddings.add(ArticleEmbedding.builder()
                    .newsArticleId(article.getId())
                    .embeddingModel(embeddingModel)
                    .embeddingVersion(embeddingVersion)
                    .chunkIndex(chunk.getChunkIndex())
                    .chunkText(chunk.getChunkText())
                    .tokenCount(chunk.getTokenCount())
                    .embedding(vectors.get(i))
                    .build());
        }

        return embeddings;
    }

    private int handleBatchSaveFailure(List<NewsArticle> articles, String errorMessage) {
        int failCount = 0;
        for (NewsArticle article : articles) {
            try {
                persistenceService.markFailed(article.getId(), "배치 저장 실패: " + errorMessage);
                failCount++;
            } catch (Exception e) {
                log.error("개별 실패 마킹도 실패 - articleId: {}, error: {}", article.getId(), e.getMessage());
                failCount++;
            }
        }
        return failCount;
    }
}
