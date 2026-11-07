package com.solv.wefin.domain.chat.aiChat.dto.info;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AiChatInfo(
        Long messageId,
        UUID userId,
        String role,
        String content,
        OffsetDateTime createdAt,
        List<AiChatParsedSectionInfo> parsedSections
) {
    public AiChatInfo(
            Long messageId,
            UUID userId,
            String role,
            String content,
            OffsetDateTime createdAt
    ) {
        this(messageId, userId, role, content, createdAt, List.of());
    }
}
