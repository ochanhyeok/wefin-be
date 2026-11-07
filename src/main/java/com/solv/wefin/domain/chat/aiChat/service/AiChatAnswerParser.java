package com.solv.wefin.domain.chat.aiChat.service;

import com.solv.wefin.domain.chat.aiChat.dto.info.AiChatParsedSectionInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class AiChatAnswerParser {

    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\d+[.)]\\s+(.+)$");
    private static final Pattern BULLET = Pattern.compile("^[-*\\u2022]\\s+(.+)$");

    private AiChatAnswerParser() {
    }

    public static List<AiChatParsedSectionInfo> parse(String role, String content) {
        if (!"AI".equals(role) || content == null || content.isBlank()) {
            return List.of();
        }

        List<AiChatParsedSectionInfo> sections = new ArrayList<>();
        String currentTitle = null;
        List<String> currentItems = new ArrayList<>();

        for (String rawLine : content.replace("\r\n", "\n").split("\n")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                continue;
            }

            String heading = toHeading(line);
            if (heading != null) {
                addSection(sections, currentTitle, currentItems);
                currentTitle = heading;
                currentItems = new ArrayList<>();
                continue;
            }

            String item = toItem(line);
            if (currentTitle == null) {
                currentTitle = "답변";
            }
            currentItems.add(item);
        }

        addSection(sections, currentTitle, currentItems);
        return List.copyOf(sections);
    }

    private static String toHeading(String line) {
        String normalized = stripMarkdown(line);

        if (line.startsWith("#")) {
            return stripMarkdown(line.replaceFirst("^#+\\s*", ""));
        }

        var numberedMatcher = NUMBERED_HEADING.matcher(normalized);
        if (numberedMatcher.matches()) {
            String value = stripMarkdown(numberedMatcher.group(1));
            return isHeadingLike(value) ? value : null;
        }

        return isHeadingLike(normalized) ? normalized : null;
    }

    private static String toItem(String line) {
        var bulletMatcher = BULLET.matcher(line);
        if (bulletMatcher.matches()) {
            return stripMarkdown(bulletMatcher.group(1));
        }

        return stripMarkdown(line);
    }

    private static boolean isHeadingLike(String line) {
        if (line.length() > 32) {
            return false;
        }

        if (line.endsWith(".") || line.endsWith("?") || line.endsWith("!")) {
            return false;
        }

        return !BULLET.matcher(line).matches();
    }

    private static String stripMarkdown(String value) {
        return value
                .replaceAll("\\*\\*(.*?)\\*\\*", "$1")
                .replaceAll("__(.*?)__", "$1")
                .replaceAll("`", "")
                .trim();
    }

    private static void addSection(
            List<AiChatParsedSectionInfo> sections,
            String title,
            List<String> items
    ) {
        if (title == null || items.isEmpty()) {
            return;
        }

        sections.add(new AiChatParsedSectionInfo(title, List.copyOf(items)));
    }
}
