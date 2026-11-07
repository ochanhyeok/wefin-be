package com.solv.wefin.domain.news.crawl;

import com.solv.wefin.domain.news.config.NewsBatchProperties;
import com.solv.wefin.domain.news.ingestion.service.ArticleCrawlPersistenceService;
import com.solv.wefin.domain.news.ingestion.service.ArticleCrawlService;
import com.solv.wefin.domain.news.ingestion.crawler.ArticleContentExtractor;
import com.solv.wefin.domain.news.article.entity.NewsArticle;
import com.solv.wefin.domain.news.article.entity.NewsArticle.CrawlStatus;
import com.solv.wefin.domain.news.article.repository.NewsArticleRepository;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArticleCrawlServiceTest {

    private static final String SAMPLE_HTML_WITH_THUMBNAIL = """
            <html>
            <head>
                <meta property="og:image" content="https://example.com/thumb/%d.jpg"/>
            </head>
            <body>
                <article>%s</article>
            </body>
            </html>
            """;
    private static final String SAMPLE_HTML_WITHOUT_THUMBNAIL = """
            <html>
            <head></head>
            <body>
                <article>%s</article>
            </body>
            </html>
            """;
    @Mock
    private NewsArticleRepository newsArticleRepository;
    @Mock
    private ArticleCrawlPersistenceService persistenceService;
    @Mock
    private RestTemplate newsRestTemplate;
    @Mock
    private ArticleContentExtractor extractor;
    @Captor
    private ArgumentCaptor<Long> articleIdCaptor;
    @Captor
    private ArgumentCaptor<String> contentCaptor;
    @Captor
    private ArgumentCaptor<String> thumbnailCaptor;
    private ArticleCrawlService articleCrawlService;

    @BeforeEach
    void setUp() {
        articleCrawlService = new ArticleCrawlService(
                newsArticleRepository,
                persistenceService,
                List.of(extractor),
                newsRestTemplate,
                new NewsBatchProperties(500, 500, 500, 500, 50, 500, 50)
        );
    }

    @Test
    void 수집된_10건_기사를_크롤링하면_올바른_본문과_썸네일이_저장된다() {
        // given
        List<NewsArticle> articles = createPendingArticles(10);
        stubFindCrawlTargets(articles);
        when(extractor.supports(anyString())).thenReturn(true);

        for (int i = 0; i < 10; i++) {
            String body = "본문 내용 기사번호=" + i;
            String html = String.format(SAMPLE_HTML_WITH_THUMBNAIL, i, body);
            stubHtmlResponse("https://example.com/news/" + i, html);
        }
        // thenAnswer로 URL별 다른 본문 반환
        when(extractor.extract(any(Document.class))).thenAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            return doc.select("article").text();
        });

        // when
        articleCrawlService.crawlPendingArticles();

        // then — 호출 횟수 + id-content 매핑으로 순서 무관 검증
        verify(persistenceService, times(10))
                .saveCrawlSuccess(articleIdCaptor.capture(), contentCaptor.capture(), thumbnailCaptor.capture());
        verify(persistenceService, never()).saveCrawlFailure(anyLong(), anyString());

        Map<Long, String> savedContentMap = IntStream.range(0, 10).boxed()
                .collect(Collectors.toMap(
                        i -> articleIdCaptor.getAllValues().get(i),
                        i -> contentCaptor.getAllValues().get(i)));

        Map<Long, String> savedThumbnailMap = IntStream.range(0, 10).boxed()
                .collect(Collectors.toMap(
                        i -> articleIdCaptor.getAllValues().get(i),
                        i -> thumbnailCaptor.getAllValues().get(i)));

        for (int i = 0; i < 10; i++) {
            long expectedId = i + 1;
            assertThat(savedContentMap.get(expectedId)).contains("기사번호=" + i);
            assertThat(savedThumbnailMap.get(expectedId)).isEqualTo("https://example.com/thumb/" + i + ".jpg");
        }
    }

    @Test
    void 수집된_10건_중_5건의_HTML_응답이_비어있으면_5건은_성공하고_5건은_실패로_저장된다() {
        // given
        List<NewsArticle> articles = createPendingArticles(10);
        stubFindCrawlTargets(articles);
        when(extractor.supports(anyString())).thenReturn(true);
        when(extractor.extract(any(Document.class))).thenAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            return doc.select("article").text();
        });

        for (int i = 0; i < 10; i++) {
            if (i < 5) {
                String body = "본문 내용 " + i;
                String html = String.format(SAMPLE_HTML_WITH_THUMBNAIL, i, body);
                stubHtmlResponse("https://example.com/news/" + i, html);
            } else {
                stubHtmlResponse("https://example.com/news/" + i, "");
            }
        }

        // when
        articleCrawlService.crawlPendingArticles();

        // then
        verify(persistenceService, times(5)).saveCrawlSuccess(anyLong(), anyString(), anyString());
        verify(persistenceService, times(5)).saveCrawlFailure(anyLong(), eq("Empty HTML response"));
    }

    @Test
    void 본문_추출에_실패하면_실패_상태를_저장한다() {
        // given
        List<NewsArticle> articles = createPendingArticles(10);
        stubFindCrawlTargets(articles);
        when(extractor.supports(anyString())).thenReturn(true);
        when(extractor.extract(any(Document.class))).thenReturn(null);

        for (int i = 0; i < 10; i++) {
            stubHtmlResponse("https://example.com/news/" + i, "<html><body>no article</body></html>");
        }

        // when
        articleCrawlService.crawlPendingArticles();

        // then
        verify(persistenceService, never()).saveCrawlSuccess(anyLong(), anyString(), anyString());
        verify(persistenceService, times(10)).saveCrawlFailure(anyLong(), eq("Content extraction returned empty"));
    }

    @Test
    void 네트워크_예외_발생_시_에러메시지와_함께_실패_저장된다() {
        // given
        List<NewsArticle> articles = createPendingArticles(10);
        stubFindCrawlTargets(articles);

        for (int i = 0; i < 10; i++) {
            when(newsRestTemplate.exchange(
                    eq("https://example.com/news/" + i),
                    eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RuntimeException("Connection refused"));
        }

        // when
        articleCrawlService.crawlPendingArticles();

        // then
        verify(persistenceService, never()).saveCrawlSuccess(anyLong(), anyString(), anyString());
        verify(persistenceService, times(10)).saveCrawlFailure(anyLong(), eq("Connection refused"));
    }

    @Test
    void PENDING_기사가_없으면_HTTP_호출과_추출을_건너뛴다() {
        // given
        stubFindCrawlTargets(List.of());

        // when
        articleCrawlService.crawlPendingArticles();

        // then
        verify(persistenceService, never()).saveCrawlSuccess(anyLong(), anyString(), anyString());
        verify(persistenceService, never()).saveCrawlFailure(anyLong(), anyString());
        verifyNoInteractions(newsRestTemplate);
        verifyNoInteractions(extractor);
    }

    @Test
    void 지원하는_extractor가_없으면_실패_상태를_저장한다() {
        // given
        List<NewsArticle> articles = createPendingArticles(3);
        stubFindCrawlTargets(articles);
        when(extractor.supports(anyString())).thenReturn(false);

        for (int i = 0; i < 3; i++) {
            stubHtmlResponse("https://example.com/news/" + i, "<html><body>content</body></html>");
        }

        // when
        articleCrawlService.crawlPendingArticles();

        // then
        verify(extractor, never()).extract(any());
        verify(persistenceService, times(3)).saveCrawlFailure(anyLong(), eq("Content extraction returned empty"));
    }

    @Test
    void 썸네일이_없는_HTML이면_null_썸네일로_성공_저장된다() {
        // given
        List<NewsArticle> articles = createPendingArticles(1);
        stubFindCrawlTargets(articles);
        when(extractor.supports(anyString())).thenReturn(true);
        when(extractor.extract(any(Document.class))).thenReturn("본문 내용");

        String html = String.format(SAMPLE_HTML_WITHOUT_THUMBNAIL, "본문 내용");
        stubHtmlResponse("https://example.com/news/0", html);

        // when
        articleCrawlService.crawlPendingArticles();

        // then
        verify(persistenceService).saveCrawlSuccess(eq(1L), eq("본문 내용"), isNull());
    }

    private void stubFindCrawlTargets(List<NewsArticle> articles) {
        when(newsArticleRepository.findByCrawlStatusInAndCrawlRetryCountLessThanOrderByCollectedAtDesc(
                eq(List.of(CrawlStatus.PENDING, CrawlStatus.FAILED)), eq(3), any()))
                .thenReturn(articles);
    }

    private void stubHtmlResponse(String url, String html) {
        when(newsRestTemplate.exchange(
                eq(url), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(html));
    }

    private List<NewsArticle> createPendingArticles(int count) {
        List<NewsArticle> articles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            NewsArticle article = NewsArticle.builder()
                    .rawNewsArticleId((long) i)
                    .publisherName("example.com")
                    .title("테스트 기사 " + i)
                    .content("요약 " + i)
                    .originalUrl("https://example.com/news/" + i)
                    .dedupKey("url:hash" + i)
                    .build();

            ReflectionTestUtils.setField(article, "id", (long) (i + 1));
            ReflectionTestUtils.setField(article, "crawlStatus", CrawlStatus.PENDING);
            articles.add(article);
        }
        return articles;
    }
}
