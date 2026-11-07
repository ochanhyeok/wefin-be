package com.solv.wefin.domain.news.embedding.service;

import com.solv.wefin.domain.news.config.NewsBatchProperties;
import com.solv.wefin.domain.news.embedding.chunk.ArticleChunker;
import com.solv.wefin.domain.news.embedding.client.OpenAiEmbeddingClient;
import com.solv.wefin.domain.news.embedding.entity.ArticleEmbedding;
import com.solv.wefin.domain.news.article.entity.NewsArticle;
import com.solv.wefin.domain.news.article.entity.NewsArticle.CrawlStatus;
import com.solv.wefin.domain.news.article.entity.NewsArticle.EmbeddingStatus;
import com.solv.wefin.domain.news.article.repository.NewsArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private NewsArticleRepository newsArticleRepository;
    @Mock
    private OpenAiEmbeddingClient openAiEmbeddingClient;
    @Mock
    private ArticleChunker articleChunker;
    @Mock
    private EmbeddingPersistenceService persistenceService;
    @Captor
    private ArgumentCaptor<List<ArticleEmbedding>> embeddingsCaptor;
    @Captor
    private ArgumentCaptor<List<NewsArticle>> articlesCaptor;

    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        embeddingService = new EmbeddingService(
                newsArticleRepository, openAiEmbeddingClient, articleChunker, persistenceService,
                new NewsBatchProperties(500, 500, 500, 500, 50, 500, 50));
        ReflectionTestUtils.setField(embeddingService, "embeddingModel", "text-embedding-3-small");
        ReflectionTestUtils.setField(embeddingService, "embeddingVersion", "v1");
    }

    @Test
    @DisplayName("대상 기사 3건이 있으면 청킹 → API 호출 → 저장을 수행한다")
    void generatePendingEmbeddings_success() {
        // given
        List<NewsArticle> articles = createArticles(3);
        stubFindTargets(articles);

        List<ArticleChunker.Chunk> singleChunk = List.of(
                ArticleChunker.Chunk.builder().chunkIndex(0).chunkText("테스트 텍스트").tokenCount(10).build());
        given(articleChunker.chunk(anyString(), anyString())).willReturn(singleChunk);

        float[] vector = new float[1536];
        given(openAiEmbeddingClient.getEmbeddings(anyList())).willReturn(List.of(vector));

        // when
        embeddingService.generatePendingEmbeddings();

        // then
        verify(persistenceService).markProcessing(articles);
        verify(persistenceService).saveEmbeddingsBatch(embeddingsCaptor.capture(), articlesCaptor.capture());
        assertThat(embeddingsCaptor.getValue()).hasSize(3);
        assertThat(articlesCaptor.getValue()).hasSize(3);
        verify(persistenceService, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("대상 기사가 없으면 아무 작업도 하지 않는다")
    void generatePendingEmbeddings_noTargets() {
        // given
        stubFindTargets(List.of());

        // when
        embeddingService.generatePendingEmbeddings();

        // then
        verifyNoInteractions(openAiEmbeddingClient);
        verifyNoInteractions(articleChunker);
        verify(persistenceService, never()).markProcessing(anyList());
    }

    @Test
    @DisplayName("OpenAI API 실패 시 해당 기사만 FAILED로 마킹하고 나머지는 진행한다")
    void generatePendingEmbeddings_partialFailure() {
        // given
        List<NewsArticle> articles = createArticles(3);
        stubFindTargets(articles);

        List<ArticleChunker.Chunk> singleChunk = List.of(
                ArticleChunker.Chunk.builder().chunkIndex(0).chunkText("텍스트").tokenCount(5).build());
        given(articleChunker.chunk(anyString(), anyString())).willReturn(singleChunk);

        float[] vector = new float[1536];
        given(openAiEmbeddingClient.getEmbeddings(anyList()))
                .willReturn(List.of(vector))
                .willThrow(new RuntimeException("API 오류"))
                .willReturn(List.of(vector));

        // when
        embeddingService.generatePendingEmbeddings();

        // then — 1건 실패, 2건 성공
        verify(persistenceService).markFailed(eq(2L), contains("API 오류"));
        verify(persistenceService).saveEmbeddingsBatch(embeddingsCaptor.capture(), articlesCaptor.capture());
        assertThat(embeddingsCaptor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("청크 분할 결과가 비어있으면 해당 기사를 FAILED로 마킹한다")
    void generatePendingEmbeddings_emptyChunks() {
        // given
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        given(articleChunker.chunk(anyString(), anyString())).willReturn(List.of());

        // when
        embeddingService.generatePendingEmbeddings();

        // then
        verify(persistenceService).markFailed(eq(1L), contains("청크 분할 결과가 비어있습니다"));
        verify(openAiEmbeddingClient, never()).getEmbeddings(anyList());
    }

    @Test
    @DisplayName("여러 청크가 있는 기사는 청크 수만큼 임베딩을 생성한다")
    void generatePendingEmbeddings_multipleChunks() {
        // given
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        List<ArticleChunker.Chunk> threeChunks = List.of(
                ArticleChunker.Chunk.builder().chunkIndex(0).chunkText("첫 번째").tokenCount(10).build(),
                ArticleChunker.Chunk.builder().chunkIndex(1).chunkText("두 번째").tokenCount(10).build(),
                ArticleChunker.Chunk.builder().chunkIndex(2).chunkText("세 번째").tokenCount(10).build());
        given(articleChunker.chunk(anyString(), anyString())).willReturn(threeChunks);

        float[] vector = new float[1536];
        given(openAiEmbeddingClient.getEmbeddings(anyList()))
                .willReturn(List.of(vector, vector, vector));

        // when
        embeddingService.generatePendingEmbeddings();

        // then
        verify(persistenceService).saveEmbeddingsBatch(embeddingsCaptor.capture(), articlesCaptor.capture());
        assertThat(embeddingsCaptor.getValue()).hasSize(3);
        assertThat(embeddingsCaptor.getValue().get(0).getChunkIndex()).isZero();
        assertThat(embeddingsCaptor.getValue().get(1).getChunkIndex()).isEqualTo(1);
        assertThat(embeddingsCaptor.getValue().get(2).getChunkIndex()).isEqualTo(2);
    }

    private void stubFindTargets(List<NewsArticle> articles) {
        given(newsArticleRepository.findEmbeddingTargets(
                        eq(CrawlStatus.SUCCESS),
                        eq(List.of(EmbeddingStatus.PENDING, EmbeddingStatus.FAILED)),
                        eq(EmbeddingStatus.PROCESSING),
                        eq(3), any(), any(), any()))
                .willReturn(articles);
    }

    private List<NewsArticle> createArticles(int count) {
        List<NewsArticle> articles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            NewsArticle article = NewsArticle.builder()
                    .rawNewsArticleId((long) i)
                    .publisherName("test")
                    .title("테스트 기사 " + i)
                    .content("본문 내용 " + i)
                    .originalUrl("https://example.com/" + i)
                    .dedupKey("key" + i)
                    .build();
            ReflectionTestUtils.setField(article, "id", (long) (i + 1));
            ReflectionTestUtils.setField(article, "crawlStatus", CrawlStatus.SUCCESS);
            ReflectionTestUtils.setField(article, "embeddingStatus", EmbeddingStatus.PENDING);
            articles.add(article);
        }
        return articles;
    }
}
