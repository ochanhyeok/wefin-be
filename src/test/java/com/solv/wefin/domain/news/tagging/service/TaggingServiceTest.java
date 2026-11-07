package com.solv.wefin.domain.news.tagging.service;

import com.solv.wefin.domain.news.article.entity.NewsArticle;
import com.solv.wefin.domain.news.article.entity.NewsArticle.CrawlStatus;
import com.solv.wefin.domain.news.article.entity.NewsArticle.TaggingStatus;
import com.solv.wefin.domain.news.article.entity.NewsArticleTag;
import com.solv.wefin.domain.news.article.repository.NewsArticleRepository;
import com.solv.wefin.domain.news.config.NewsBatchProperties;
import com.solv.wefin.domain.news.tagging.client.OpenAiTaggingClient;
import com.solv.wefin.domain.news.tagging.dto.TaggingResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TaggingServiceTest {

    @Mock
    private NewsArticleRepository newsArticleRepository;
    @Mock
    private OpenAiTaggingClient openAiTaggingClient;
    @Mock
    private TaggingPersistenceService persistenceService;
    @Mock
    private StockCodeValidator stockCodeValidator;
    @Captor
    private ArgumentCaptor<List<NewsArticleTag>> tagsCaptor;
    @Captor
    private ArgumentCaptor<List<NewsArticle>> articlesCaptor;

    private TaggingService taggingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        taggingService = new TaggingService(newsArticleRepository, openAiTaggingClient,
                persistenceService, stockCodeValidator,
                new NewsBatchProperties(500, 500, 500, 500, 50, 500, 50));
        lenient().when(stockCodeValidator.loadStockMap())
                .thenReturn(java.util.Map.of(
                        "005930", "삼성전자",
                        "000660", "SK하이닉스",
                        "035720", "카카오"));
    }

    @Test
    @DisplayName("대상 기사 3건이 있으면 태깅 → 저장을 수행한다")
    void tagPendingArticles_success() throws Exception {
        // given
        List<NewsArticle> articles = createArticles(3);
        stubFindTargets(articles);

        TaggingResult result = createTaggingResult();
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        // when
        taggingService.tagPendingArticles();

        // then
        verify(persistenceService).markProcessing(articles);
        verify(persistenceService).saveTagsBatch(tagsCaptor.capture(), articlesCaptor.capture());
        assertThat(tagsCaptor.getValue()).isNotEmpty();
        assertThat(articlesCaptor.getValue()).hasSize(3);
        verify(persistenceService, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("대상 기사가 없으면 아무 작업도 하지 않는다")
    void tagPendingArticles_noTargets() {
        // given
        stubFindTargets(List.of());

        // when
        taggingService.tagPendingArticles();

        // then
        verifyNoInteractions(openAiTaggingClient);
        verify(persistenceService, never()).markProcessing(anyList());
    }

    @Test
    @DisplayName("OpenAI API 실패 시 해당 기사만 FAILED로 마킹하고 나머지는 진행한다")
    void tagPendingArticles_partialFailure() throws Exception {
        // given
        List<NewsArticle> articles = createArticles(3);
        stubFindTargets(articles);

        TaggingResult result = createTaggingResult();
        given(openAiTaggingClient.analyzeTags(anyString(), anyString()))
                .willReturn(result)
                .willThrow(new RuntimeException("API 오류"))
                .willReturn(result);

        // when
        taggingService.tagPendingArticles();

        // then
        verify(persistenceService).markFailed(eq(2L), contains("API 오류"));
        verify(persistenceService).saveTagsBatch(tagsCaptor.capture(), articlesCaptor.capture());
        assertThat(articlesCaptor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("태깅 결과가 비어있으면 해당 기사를 FAILED로 마킹한다")
    void tagPendingArticles_emptyResult() throws Exception {
        // given
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        TaggingResult emptyResult = new TaggingResult();
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(emptyResult);

        // when
        taggingService.tagPendingArticles();

        // then
        verify(persistenceService).markFailed(eq(1L), contains("태깅 결과가 비어있습니다"));
        verify(openAiTaggingClient).analyzeTags(anyString(), anyString());
    }

    @Test
    @DisplayName("태깅 결과에서 STOCK/SECTOR/TOPIC 태그가 모두 생성된다")
    void tagPendingArticles_allTagTypes() throws Exception {
        // given
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        TaggingResult result = createTaggingResult();
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        // when
        taggingService.tagPendingArticles();

        // then
        verify(persistenceService).saveTagsBatch(tagsCaptor.capture(), any());
        List<NewsArticleTag> tags = tagsCaptor.getValue();
        assertThat(tags).extracting(NewsArticleTag::getTagType)
                .containsExactlyInAnyOrder(
                        NewsArticleTag.TagType.STOCK,
                        NewsArticleTag.TagType.SECTOR,
                        NewsArticleTag.TagType.TOPIC);
    }

    @Test
    @DisplayName("마스터에 없는 STOCK 코드는 스킵되고 SECTOR/TOPIC은 그대로 저장된다")
    void tagPendingArticles_invalidStockCode_skippedButOthersKept() throws Exception {
        // given
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        // stocks 중 마스터에 없는 코드(357600) 포함
        String json = """
                {
                  "stocks": [
                    {"code": "005930", "name": "삼성전자"},
                    {"code": "357600", "name": "쿠팡"}
                  ],
                  "sectors": [{"code": "SEMICONDUCTOR", "name": "반도체"}],
                  "topics": [{"code": "EARNINGS", "name": "실적"}]
                }
                """;
        TaggingResult result = objectMapper.readValue(json, TaggingResult.class);
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        // when
        taggingService.tagPendingArticles();

        // then — 유효한 STOCK 1개 + SECTOR 1개 + TOPIC 1개만 저장
        verify(persistenceService).saveTagsBatch(tagsCaptor.capture(), any());
        List<NewsArticleTag> savedTags = tagsCaptor.getValue();
        assertThat(savedTags).hasSize(3);
        assertThat(savedTags).extracting(NewsArticleTag::getTagCode)
                .doesNotContain("357600")
                .contains("005930", "SEMICONDUCTOR", "EARNINGS");
    }

    @Test
    @DisplayName("STOCK 코드가 null이면 스킵한다")
    void tagPendingArticles_nullStockCode_skipped() throws Exception {
        // given
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        String json = """
                {
                  "stocks": [{"code": null, "name": "코드없음"}],
                  "sectors": [{"code": "SEMICONDUCTOR", "name": "반도체"}],
                  "topics": [{"code": "EARNINGS", "name": "실적"}]
                }
                """;
        TaggingResult result = objectMapper.readValue(json, TaggingResult.class);
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        // when
        taggingService.tagPendingArticles();

        // then — STOCK 없이 SECTOR/TOPIC만 저장
        verify(persistenceService).saveTagsBatch(tagsCaptor.capture(), any());
        List<NewsArticleTag> savedTags = tagsCaptor.getValue();
        assertThat(savedTags).extracting(NewsArticleTag::getTagType)
                .doesNotContain(NewsArticleTag.TagType.STOCK);
    }

    @Test
    @DisplayName("모든 STOCK 코드가 마스터에 없어도 SECTOR/TOPIC이 있으면 그 기사는 성공 처리")
    void tagPendingArticles_allStockCodesInvalid_butOtherTagsExist_succeeds() throws Exception {
        // given
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        String json = """
                {
                  "stocks": [
                    {"code": "999999", "name": "없는종목"},
                    {"code": "888888", "name": "또없는종목"}
                  ],
                  "sectors": [{"code": "SEMICONDUCTOR", "name": "반도체"}],
                  "topics": [{"code": "EARNINGS", "name": "실적"}]
                }
                """;
        TaggingResult result = objectMapper.readValue(json, TaggingResult.class);
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        // when
        taggingService.tagPendingArticles();

        // then — SECTOR/TOPIC 2개만 저장, 기사는 성공
        verify(persistenceService).saveTagsBatch(tagsCaptor.capture(), articlesCaptor.capture());
        assertThat(tagsCaptor.getValue()).hasSize(2);
        assertThat(tagsCaptor.getValue()).extracting(NewsArticleTag::getTagType)
                .doesNotContain(NewsArticleTag.TagType.STOCK);
        assertThat(articlesCaptor.getValue()).hasSize(1);
        verify(persistenceService, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("필터링 후 유효 태그가 0개면 기사를 FAILED로 마킹한다 (PROCESSING 잔류 방지)")
    void tagPendingArticles_zeroValidTags_marksFailed() throws Exception {
        // given: stocks만 모두 invalid, sectors/topics 비어있음
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        String json = """
                {
                  "stocks": [{"code": "999999", "name": "없는종목"}],
                  "sectors": [],
                  "topics": []
                }
                """;
        TaggingResult result = objectMapper.readValue(json, TaggingResult.class);
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        // when
        taggingService.tagPendingArticles();

        // then — saveTagsBatch 호출 안 됨, markFailed로 PROCESSING 해소
        verify(persistenceService, never()).saveTagsBatch(anyList(), anyList());
        verify(persistenceService).markFailed(eq(1L), contains("유효 태그 0개"));
    }

    @Test
    @DisplayName("STOCK 코드가 유효하면 tagName은 마스터의 canonical name으로 강제된다")
    void tagPendingArticles_stockTagName_usesMasterCanonicalName() throws Exception {
        // given: AI가 올바른 코드에 잘못된 이름 ("삼전")을 붙인 케이스
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        String json = """
                {
                  "stocks": [{"code": "005930", "name": "삼전"}],
                  "sectors": [{"code": "TECH", "name": "기술"}],
                  "topics": [{"code": "EARNINGS", "name": "실적"}]
                }
                """;
        TaggingResult result = objectMapper.readValue(json, TaggingResult.class);
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        // when
        taggingService.tagPendingArticles();

        // then — tagName은 AI가 보낸 "삼전"이 아니라 마스터의 "삼성전자"
        verify(persistenceService).saveTagsBatch(tagsCaptor.capture(), any());
        NewsArticleTag stockTag = tagsCaptor.getValue().stream()
                .filter(t -> t.getTagType() == NewsArticleTag.TagType.STOCK)
                .findFirst().orElseThrow();
        assertThat(stockTag.getTagCode()).isEqualTo("005930");
        assertThat(stockTag.getTagName()).isEqualTo("삼성전자");
    }

    @Test
    @DisplayName("유효 태그 0개일 때 summary/relevance는 persist되지 않는다 (부작용 분리)")
    void tagPendingArticles_zeroValidTags_doesNotMutateArticle() throws Exception {
        // given: 유효 태그 0개 AI 응답
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        String json = """
                {
                  "stocks": [{"code": "999999", "name": "없는종목"}],
                  "sectors": [],
                  "topics": [],
                  "summary": "무시되어야 할 요약",
                  "relevance": "IRRELEVANT"
                }
                """;
        TaggingResult result = objectMapper.readValue(json, TaggingResult.class);
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        // when
        taggingService.tagPendingArticles();

        // then — markFailed 호출되고, article 엔티티의 summary/relevance는 변경 없음
        verify(persistenceService).markFailed(eq(1L), contains("유효 태그 0개"));
        verify(persistenceService, never()).saveTagsBatch(anyList(), anyList());
        NewsArticle original = articles.get(0);
        assertThat(original.getSummary()).isNull();
        assertThat(original.getRelevance()).isEqualTo(NewsArticle.RelevanceStatus.PENDING); // default 유지
    }

    @Test
    @DisplayName("유효 태그가 있을 때만 summary/relevance가 엔티티에 반영된다")
    void tagPendingArticles_hasValidTags_mutatesArticle() throws Exception {
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        String json = """
                {
                  "stocks": [{"code": "005930", "name": "삼성전자"}],
                  "sectors": [{"code": "TECH", "name": "기술"}],
                  "topics": [{"code": "EARNINGS", "name": "실적"}],
                  "summary": "삼성전자 실적 호조",
                  "relevance": "FINANCIAL"
                }
                """;
        TaggingResult result = objectMapper.readValue(json, TaggingResult.class);
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        taggingService.tagPendingArticles();

        NewsArticle article = articles.get(0);
        assertThat(article.getSummary()).isEqualTo("삼성전자 실적 호조");
        assertThat(article.getRelevance()).isEqualTo(NewsArticle.RelevanceStatus.FINANCIAL);
    }

    @Test
    @DisplayName("같은 STOCK 코드가 중복 반환되면 한 번만 저장된다")
    void tagPendingArticles_duplicateStockCode_deduplicated() throws Exception {
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        String json = """
                {
                  "stocks": [
                    {"code": "005930", "name": "삼성전자"},
                    {"code": "005930", "name": "삼성"}
                  ],
                  "sectors": [{"code": "TECH", "name": "기술"}],
                  "topics": [{"code": "EARNINGS", "name": "실적"}]
                }
                """;
        TaggingResult result = objectMapper.readValue(json, TaggingResult.class);
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        taggingService.tagPendingArticles();

        verify(persistenceService).saveTagsBatch(tagsCaptor.capture(), any());
        long stockCount = tagsCaptor.getValue().stream()
                .filter(t -> t.getTagType() == NewsArticleTag.TagType.STOCK)
                .count();
        assertThat(stockCount).isEqualTo(1);
    }

    @Test
    @DisplayName("공백 포함 STOCK 코드는 trim 후 매칭된다")
    void tagPendingArticles_whitespaceInStockCode_trimmed() throws Exception {
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        String json = """
                {
                  "stocks": [{"code": " 005930 ", "name": "삼성전자"}],
                  "sectors": [{"code": "TECH", "name": "기술"}],
                  "topics": [{"code": "EARNINGS", "name": "실적"}]
                }
                """;
        TaggingResult result = objectMapper.readValue(json, TaggingResult.class);
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        taggingService.tagPendingArticles();

        verify(persistenceService).saveTagsBatch(tagsCaptor.capture(), any());
        assertThat(tagsCaptor.getValue()).extracting(NewsArticleTag::getTagCode)
                .contains("005930"); // trim 후 매칭 + 저장된 코드도 trim된 값
    }

    @Test
    @DisplayName("SECTOR/TOPIC 코드는 대소문자/공백 정규화 + 중복 제거된다")
    void tagPendingArticles_sectorTopic_normalizedAndDeduped() throws Exception {
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        String json = """
                {
                  "stocks": [{"code": "005930", "name": "삼성전자"}],
                  "sectors": [
                    {"code": "Tech", "name": "기술"},
                    {"code": " TECH ", "name": "Technology"}
                  ],
                  "topics": [
                    {"code": "earnings", "name": "실적"},
                    {"code": "EARNINGS", "name": "실적"}
                  ]
                }
                """;
        TaggingResult result = objectMapper.readValue(json, TaggingResult.class);
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        taggingService.tagPendingArticles();

        verify(persistenceService).saveTagsBatch(tagsCaptor.capture(), any());
        List<NewsArticleTag> saved = tagsCaptor.getValue();

        // SECTOR는 TECH 1개 (대소문자/공백 중복 제거)
        assertThat(saved.stream().filter(t -> t.getTagType() == NewsArticleTag.TagType.SECTOR))
                .hasSize(1)
                .allMatch(t -> t.getTagCode().equals("TECH"));

        // TOPIC은 EARNINGS 1개
        assertThat(saved.stream().filter(t -> t.getTagType() == NewsArticleTag.TagType.TOPIC))
                .hasSize(1)
                .allMatch(t -> t.getTagCode().equals("EARNINGS"));
    }

    @Test
    @DisplayName("유효 태그 0개 경로에서 loadStockMap → markProcessing → markFailed 순서 보장")
    void tagPendingArticles_zeroValidTags_inOrder() throws Exception {
        List<NewsArticle> articles = createArticles(1);
        stubFindTargets(articles);

        String json = """
                {
                  "stocks": [{"code": "999999", "name": "없는종목"}],
                  "sectors": [],
                  "topics": []
                }
                """;
        TaggingResult result = objectMapper.readValue(json, TaggingResult.class);
        given(openAiTaggingClient.analyzeTags(anyString(), anyString())).willReturn(result);

        taggingService.tagPendingArticles();

        InOrder inOrder = inOrder(stockCodeValidator, persistenceService);
        inOrder.verify(stockCodeValidator).loadStockMap();
        inOrder.verify(persistenceService).markProcessing(anyList());
        inOrder.verify(persistenceService).markFailed(eq(1L), anyString());
    }

    @Test
    @DisplayName("종목 마스터 로드 실패 시 예외가 전파되고 markProcessing은 호출되지 않는다")
    void tagPendingArticles_stockMapLoadFailure_throwsAndDoesNotMarkProcessing() {
        // given
        List<NewsArticle> articles = createArticles(2);
        stubFindTargets(articles);
        given(stockCodeValidator.loadStockMap())
                .willThrow(new RuntimeException("DB 일시 장애"));

        // when / then — 예외가 실제로 전파되는지 assertThrows로 고정.
        // markProcessing이 호출되지 않아야 다음 배치 틱에서 stale 대기 없이 즉시 재시도 가능
        assertThatThrownBy(() -> taggingService.tagPendingArticles())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB 일시 장애");
        verify(persistenceService, never()).markProcessing(anyList());
    }

    private void stubFindTargets(List<NewsArticle> articles) {
        given(newsArticleRepository.findTaggingTargets(
                eq(CrawlStatus.SUCCESS),
                eq(List.of(TaggingStatus.PENDING, TaggingStatus.FAILED)),
                eq(TaggingStatus.PROCESSING),
                eq(3), any(), any()))
                .willReturn(articles);
    }

    private List<NewsArticle> createArticles(int count) {
        List<NewsArticle> articles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            NewsArticle article = NewsArticle.builder()
                    .rawNewsArticleId(null)
                    .publisherName("test")
                    .title("테스트 기사 " + i)
                    .content("본문 내용 " + i)
                    .originalUrl("https://example.com/" + i)
                    .dedupKey("key" + i)
                    .build();
            ReflectionTestUtils.setField(article, "id", (long) (i + 1));
            ReflectionTestUtils.setField(article, "crawlStatus", CrawlStatus.SUCCESS);
            ReflectionTestUtils.setField(article, "taggingStatus", TaggingStatus.PENDING);
            articles.add(article);
        }
        return articles;
    }

    private TaggingResult createTaggingResult() throws Exception {
        String json = """
                {
                  "stocks": [{"code": "005930", "name": "삼성전자"}],
                  "sectors": [{"code": "SEMICONDUCTOR", "name": "반도체"}],
                  "topics": [{"code": "EARNINGS", "name": "실적"}]
                }
                """;
        return objectMapper.readValue(json, TaggingResult.class);
    }
}
