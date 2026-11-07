package com.solv.wefin.domain.chat.aiChat.service;

import com.solv.wefin.domain.chat.aiChat.dto.info.AiChatParsedSectionInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiChatAnswerParserTest {

    @Test
    @DisplayName("AI 답변을 제목과 목록 단위로 파싱한다")
    void parse_aiAnswer() {
        // given
        String answer = """
                요약
                - 반도체 업황 회복 기대가 있습니다.
                - 단기 변동성은 주의해야 합니다.
                
                주의사항
                무리한 단기 매수는 피하는 편이 좋습니다.
                """;

        // when
        List<AiChatParsedSectionInfo> result = AiChatAnswerParser.parse("AI", answer);

        // then
        assertEquals(2, result.size());
        assertEquals("요약", result.get(0).title());
        assertEquals(List.of(
                "반도체 업황 회복 기대가 있습니다.",
                "단기 변동성은 주의해야 합니다."
        ), result.get(0).items());
        assertEquals("주의사항", result.get(1).title());
        assertEquals(List.of("무리한 단기 매수는 피하는 편이 좋습니다."), result.get(1).items());
    }

    @Test
    @DisplayName("사용자 메시지는 파싱하지 않는다")
    void parse_userMessage() {
        // given
        String answer = """
                요약
                - 사용자 입력입니다.
                """;

        // when
        List<AiChatParsedSectionInfo> result = AiChatAnswerParser.parse("USER", answer);

        // then
        assertEquals(List.of(), result);
    }

    @Test
    @DisplayName("문장 안의 굵게 마크다운을 제거한다")
    void parse_inlineBoldMarkdown() {
        // given
        String answer = """
                요약
                - **정치요인과 규제**: 글로벌 시장의 정치적 불확실성이 있습니다.
                """;

        // when
        List<AiChatParsedSectionInfo> result = AiChatAnswerParser.parse("AI", answer);

        // then
        assertEquals("정치요인과 규제: 글로벌 시장의 정치적 불확실성이 있습니다.", result.get(0).items().get(0));
    }
}
