package com.solv.wefin.domain.news.tagging.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solv.wefin.domain.news.config.dto.OpenAiChatApiResponse;
import com.solv.wefin.domain.news.tagging.dto.TaggingResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions API를 호출하여 기사에서 태그를 추출한다.
 */
@Slf4j
@Component
public class OpenAiTaggingClient {

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final int MAX_CONTENT_LENGTH = 3000; //  태깅 본문용 최대 길이

    private static final String SYSTEM_PROMPT = """
            당신은 금융 뉴스 기사를 분석하여 태그를 추출하고 한 줄 요약을 작성하는 전문가입니다.
            기사를 읽고 다음을 추출하세요.

            1. stocks: 기사에서 언급된 개별 종목 (한국 상장 종목만, 6자리 숫자 코드)
            2. sectors: 기사와 관련된 산업/섹터 (영문 코드와 한글 이름)
            3. topics: 기사의 주제/테마 (영문 코드와 한글 이름)
            4. summary: 기사 핵심 내용을 한 문장(50자 이내)으로 요약
            5. relevance: 기사가 금융/경제/투자 관련인지 판정

            규칙:
            - 각 카테고리는 최대 5개까지
            - 확실한 것만 포함 (추측 금지)
            - stocks는 한국 상장 종목의 6자리 숫자 코드만 사용한다
              · 미국 티커(NVDA, TSLA 등), 지수(코스피, S&P500), 암호화폐는 stocks에 넣지 않음
              · 미국 빅테크 뉴스는 sectors의 TECH로, 지수/시장 뉴스는 FINANCE로 분류
            - topic 코드는 대문자 영문 (예: EARNINGS, AI, REGULATION, IPO)
            - summary는 한글로 작성, 50자 이내

            sectors 규칙:
            - 기사의 핵심 주제 분야만 태깅하세요. 단순히 언급된 종목의 업종이 아닙니다.
              예: "개인 투자자 순매도" 기사에 삼천당제약이 언급되더라도, 기사 주제가 증시 동향이면 BIO가 아닌 FINANCE입니다.
            - 아래 대분류 중 반드시 1개를 sectors 첫 번째에 포함하세요:
              · FINANCE (금융/은행/보험/증권/채권/외환/증시 동향)
              · TECH (기술/반도체/IT/AI/전자/소프트웨어)
              · INDUSTRY (산업/자동차/건설/부동산/제조/방산/철강/운송)
              · ENERGY (에너지/화학/원자재)
              · BIO (바이오/제약/헬스케어)
              · CRYPTO (암호화폐/디지털자산)
              · ETC (위에 해당하지 않는 경우)
            - 대분류 외 세부 섹터(SEMICONDUCTOR, BANKING 등)는 자유롭게 추가 가능
            - 암호화폐 토큰(비트코인, 리플 등)은 stocks가 아닌 sectors의 CRYPTO로 분류
            - sector 코드는 대문자 영문

            relevance 판정 규칙:
            - "FINANCIAL": 기사가 주식, 채권, 환율, 금리, 부동산, 기업 실적, 산업 동향, 투자, 거시경제 등
              금융·경제·투자자 관점에서 의미 있는 정보를 담고 있는 경우
            - "IRRELEVANT": 연예인 가십, 정치 공방, 사회 일반(사건·사고), 스포츠, 생활정보 등
              금융/경제와 명확히 무관한 경우
            - 판정이 애매하면 "FINANCIAL"로 기울여 선택 (false negative 방지)
            - 본문에 "환율", "경제" 같은 키워드가 등장해도 핵심 주제가 연예/정치/사회면 IRRELEVANT

            반드시 아래 JSON 형식으로만 응답하세요:
            {
              "stocks": [{"code": "005930", "name": "삼성전자"}],
              "sectors": [{"code": "TECH", "name": "기술"}, {"code": "SEMICONDUCTOR", "name": "반도체"}],
              "topics": [{"code": "EARNINGS", "name": "실적"}],
              "summary": "삼성전자가 2분기 반도체 실적 호조를 발표했다.",
              "relevance": "FINANCIAL"
            }
            """;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public OpenAiTaggingClient(@Qualifier("taggingRestTemplate") RestTemplate restTemplate,
                               ObjectMapper objectMapper,
                               @Value("${openai.api-key}") String apiKey,
                               @Value("${openai.tagging.model}") String model) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * 기사 제목과 본문을 분석하여 태그를 추출한다.
     *
     * @param title   기사 제목
     * @param content 기사 본문
     * @return 추출된 태그 결과
     */
    public TaggingResult analyzeTags(String title, String content) {
        String truncatedContent = truncateContent(content);
        String userMessage = "제목: " + title + "\n\n본문: " + truncatedContent;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", model,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userMessage)
                )
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        OpenAiChatApiResponse response = restTemplate.postForObject(OPENAI_CHAT_URL, request, OpenAiChatApiResponse.class);

        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new IllegalStateException("OpenAI Tagging API 응답이 비어있습니다");
        }

        OpenAiChatApiResponse.Message message = response.getChoices().get(0).getMessage();
        if (message == null || message.getContent() == null) {
            throw new IllegalStateException("OpenAI Tagging API 응답 메시지가 비어있습니다");
        }
        return parseTaggingResult(message.getContent());
    }

    private TaggingResult parseTaggingResult(String json) {
        try {
            return objectMapper.readValue(json, TaggingResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("태깅 결과 JSON 파싱 실패: " + e.getMessage(), e);
        }
    }

    private String truncateContent(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= MAX_CONTENT_LENGTH
                ? content
                : content.substring(0, MAX_CONTENT_LENGTH);
    }

}
