package com.solv.wefin.web.chat.aiChat.dto.response;

import com.solv.wefin.domain.chat.aiChat.dto.info.AiChatInfo;
import com.solv.wefin.domain.chat.aiChat.dto.info.AiChatParsedSectionInfo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AiChatResponse(
        Long messageId,
        UUID userId,
        String role,
        String content,
        OffsetDateTime createdAt,
        List<AiChatParsedSectionInfo> parsedSections
) {
    public static AiChatResponse from(AiChatInfo info) {
        return new AiChatResponse(
                info.messageId(),
                info.userId(),
                info.role(),
                info.content(),
                info.createdAt(),
                info.parsedSections()
        );
    }
}
