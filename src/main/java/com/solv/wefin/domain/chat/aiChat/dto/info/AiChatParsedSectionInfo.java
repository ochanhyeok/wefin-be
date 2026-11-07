package com.solv.wefin.domain.chat.aiChat.dto.info;

import java.util.List;

public record AiChatParsedSectionInfo(
        String title,
        List<String> items
) {
}
