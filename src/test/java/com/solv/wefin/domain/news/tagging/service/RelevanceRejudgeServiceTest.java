package com.solv.wefin.domain.news.tagging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solv.wefin.domain.news.article.entity.NewsArticle;
import com.solv.wefin.domain.news.article.entity.NewsArticle.RelevanceStatus;
import com.solv.wefin.domain.news.article.repository.NewsArticleRepository;
import com.solv.wefin.domain.news.config.NewsBatchProperties;
import com.solv.wefin.domain.news.tagging.client.OpenAiTaggingClient;
import com.solv.wefin.domain.news.tagging.dto.TaggingResult;
import com.solv.wefin.domain.news.tagging.service.RelevanceRejudgeService.RejudgeSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import com.solv.wefin.global.error.BusinessException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RelevanceRejudgeServiceTest {

    @Mock
    private NewsArticleRepository newsArticleRepository;
    @Mock
    private OpenAiTaggingClient openAiTaggingClient;
    @Mock
    private RelevancePersistenceService persistenceService;

    private RelevanceRejudgeService rejudgeService;
    private NewsBatchProperties batchProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int TEST_REJUDGE_MAX_LIMIT = 100;

    @BeforeEach
    void setUp() {
        batchProperties = new NewsBatchProperties(500, 500, 500, 500, 50, TEST_REJUDGE_MAX_LIMIT, 50);
        rejudgeService = new RelevanceRejudgeService(newsArticleRepository, openAiTaggingClient, persistenceService,
                batchProperties);
    }

    @Test
    @DisplayName("FINANCIAL 응답을 받으면 해당 enum으로 저장한다")
    void rejudgeByIds_financial() throws Exception {
        NewsArticle article = createArticle(10L, "삼성전자 실적", "반도체 실적 호조...");
        given(newsArticleRepository.findAllById(List.of(10L))).willReturn(List.of(article));
        given(openAiTaggingClient.analyzeTags(anyString(), anyString()))
                .willReturn(parseResult("FINANCIAL"));
        given(persistenceService.saveRelevance(anyLong(), eq(RelevanceStatus.FINANCIAL))).willReturn(true);

        RejudgeSummary summary = rejudgeService.rejudgeByIds(List.of(10L));

        assertThat(summary.success()).isEqualTo(1);
        assertThat(summary.notFound()).isZero();
        verify(persistenceService).saveRelevance(10L, RelevanceStatus.FINANCIAL);
    }

    @Test
    @DisplayName("IRRELEVANT 응답을 받으면 해당 enum으로 저장한다 (조인성 케이스)")
    void rejudgeByIds_irrelevant() throws Exception {
        NewsArticle article = createArticle(302L, "조인성 SNS 논란", "배우 조인성이 환율 언급...");
        given(newsArticleRepository.findAllById(List.of(302L))).willReturn(List.of(article));
        given(openAiTaggingClient.analyzeTags(anyString(), anyString()))
                .willReturn(parseResult("IRRELEVANT"));
        given(persistenceService.saveRelevance(anyLong(), eq(RelevanceStatus.IRRELEVANT))).willReturn(true);

        RejudgeSummary summary = rejudgeService.rejudgeByIds(List.of(302L));

        assertThat(summary.success()).isEqualTo(1);
        verify(persistenceService).saveRelevance(302L, RelevanceStatus.IRRELEVANT);
    }

    @Test
    @DisplayName("AI가 예상 외 값(오타/null) 반환 시 PENDING으로 저장한다")
    void rejudgeByIds_unexpectedValue_fallsBackToPending() throws Exception {
        NewsArticle article = createArticle(1L, "title", "content");
        given(newsArticleRepository.findAllById(List.of(1L))).willReturn(List.of(article));
        given(openAiTaggingClient.analyzeTags(anyString(), anyString()))
                .willReturn(parseResult("WEIRD_VALUE"));
        given(persistenceService.saveRelevance(anyLong(), eq(RelevanceStatus.PENDING))).willReturn(true);

        rejudgeService.rejudgeByIds(List.of(1L));

        verify(persistenceService).saveRelevance(1L, RelevanceStatus.PENDING);
    }

    @Test
    @DisplayName("content가 비어있는 기사는 AI 호출 없이 스킵한다")
    void rejudgeByIds_emptyContent_skipsAiCall() {
        NewsArticle article = createArticle(1L, "title", null);
        given(newsArticleRepository.findAllById(List.of(1L))).willReturn(List.of(article));

        RejudgeSummary summary = rejudgeService.rejudgeByIds(List.of(1L));

        assertThat(summary.success()).isZero();
        assertThat(summary.skipped()).isEqualTo(1);
        verify(openAiTaggingClient, never()).analyzeTags(anyString(), anyString());
        verify(persistenceService, never()).saveRelevance(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("AI 호출 실패 시 해당 건만 실패로 처리하고 나머지는 계속 진행한다")
    void rejudgeByIds_partialFailure() throws Exception {
        NewsArticle ok = createArticle(1L, "title", "content1");
        NewsArticle bad = createArticle(2L, "title", "content2");
        given(newsArticleRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(ok, bad));

        given(openAiTaggingClient.analyzeTags(anyString(), eq("content1")))
                .willReturn(parseResult("FINANCIAL"));
        given(openAiTaggingClient.analyzeTags(anyString(), eq("content2")))
                .willThrow(new RuntimeException("OpenAI down"));
        given(persistenceService.saveRelevance(1L, RelevanceStatus.FINANCIAL)).willReturn(true);

        RejudgeSummary summary = rejudgeService.rejudgeByIds(List.of(1L, 2L));

        assertThat(summary.success()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        verify(persistenceService).saveRelevance(1L, RelevanceStatus.FINANCIAL);
        verify(persistenceService, never()).saveRelevance(eq(2L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("저장 대상 기사가 없으면(persistence false) failed로 카운트한다 (silent success 방지)")
    void rejudgeByIds_saveReturnsFalse_countsAsFailed() throws Exception {
        NewsArticle article = createArticle(1L, "title", "content");
        given(newsArticleRepository.findAllById(List.of(1L))).willReturn(List.of(article));
        given(openAiTaggingClient.analyzeTags(anyString(), anyString()))
                .willReturn(parseResult("FINANCIAL"));
        given(persistenceService.saveRelevance(anyLong(), org.mockito.ArgumentMatchers.any())).willReturn(false);

        RejudgeSummary summary = rejudgeService.rejudgeByIds(List.of(1L));

        assertThat(summary.success()).isZero();
        assertThat(summary.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("요청한 ID가 DB에 없으면 notFound로 카운트된다")
    void rejudgeByIds_missingIds_countedAsNotFound() {
        given(newsArticleRepository.findAllById(List.of(999L))).willReturn(List.of());

        RejudgeSummary summary = rejudgeService.rejudgeByIds(List.of(999L));

        assertThat(summary.requested()).isEqualTo(1);
        assertThat(summary.fetched()).isZero();
        assertThat(summary.notFound()).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 리스트 호출 시 즉시 empty summary를 반환한다")
    void rejudgeByIds_emptyList() {
        assertThat(rejudgeService.rejudgeByIds(List.of()).requested()).isZero();
        assertThat(rejudgeService.rejudgeByIds(null).requested()).isZero();
        verify(newsArticleRepository, never()).findAllById(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("rejudgePending — PENDING 기사를 배치로 가져와 재판정한다")
    void rejudgePending_batch() throws Exception {
        List<NewsArticle> pending = List.of(
                createArticle(1L, "t1", "c1"),
                createArticle(2L, "t2", "c2"));
        given(newsArticleRepository.findRejudgeTargets(eq(RelevanceStatus.PENDING), any(), eq(PageRequest.of(0, 50))))
                .willReturn(pending);
        given(openAiTaggingClient.analyzeTags(anyString(), anyString()))
                .willReturn(parseResult("FINANCIAL"));
        given(persistenceService.saveRelevance(anyLong(), eq(RelevanceStatus.FINANCIAL))).willReturn(true);

        RejudgeSummary summary = rejudgeService.rejudgePending(50);

        assertThat(summary.success()).isEqualTo(2);
        verify(persistenceService).saveRelevance(1L, RelevanceStatus.FINANCIAL);
        verify(persistenceService).saveRelevance(2L, RelevanceStatus.FINANCIAL);
    }

    @Test
    @DisplayName("rejudgePending — limit가 0 이하이거나 rejudgeMaxLimit을 초과하면 BusinessException (400)")
    void rejudgePending_invalidLimit_throws() {
        assertThatThrownBy(() -> rejudgeService.rejudgePending(0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> rejudgeService.rejudgePending(-1))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> rejudgeService.rejudgePending(batchProperties.rejudgeMaxLimit() + 1))
                .isInstanceOf(BusinessException.class);
    }

    private NewsArticle createArticle(long id, String title, String content) {
        NewsArticle article = NewsArticle.builder()
                .rawNewsArticleId(null)
                .publisherName("test")
                .title(title)
                .content(content)
                .originalUrl("https://example.com/" + id)
                .dedupKey("key" + id)
                .build();
        ReflectionTestUtils.setField(article, "id", id);
        return article;
    }

    private TaggingResult parseResult(String relevance) throws Exception {
        String json = """
                {
                  "stocks": [],
                  "sectors": [],
                  "topics": [],
                  "summary": "test",
                  "relevance": "%s"
                }
                """.formatted(relevance);
        return objectMapper.readValue(json, TaggingResult.class);
    }
}
