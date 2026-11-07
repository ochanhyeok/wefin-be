package com.solv.wefin.domain.chat.aiChat.service;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.chat.aiChat.client.OpenAiChatClient;
import com.solv.wefin.domain.chat.aiChat.dto.command.AiChatCommand;
import com.solv.wefin.domain.chat.aiChat.dto.info.AiChatInfo;
import com.solv.wefin.domain.chat.aiChat.dto.info.AiChatMessagesInfo;
import com.solv.wefin.domain.chat.aiChat.entity.AiChatMessage;
import com.solv.wefin.domain.news.cluster.entity.ClusterSummarySection;
import com.solv.wefin.domain.news.cluster.entity.NewsCluster;
import com.solv.wefin.domain.news.cluster.repository.ClusterSummarySectionRepository;
import com.solv.wefin.domain.news.cluster.repository.NewsClusterRepository;
import com.solv.wefin.domain.quest.entity.QuestEventType;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiChatService {

    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_NEWS_CONTEXT_CHARS = 4000;
    private static final String TRUNCATED_SUFFIX = "\n...(뉴스 요약이 길어 일부 생략되었습니다)";
    private static final List<NewsCluster.SummaryStatus> AVAILABLE_SUMMARY_STATUSES = List.of(
            NewsCluster.SummaryStatus.GENERATED,
            NewsCluster.SummaryStatus.STALE
    );

    private final OpenAiChatClient openAiChatClient;
    private final AiChatMessagePersistenceService aiChatMessagePersistenceService;
    private final UserRepository userRepository;
    private final QuestProgressService questProgressService;
    private final NewsClusterRepository newsClusterRepository;
    private final ClusterSummarySectionRepository clusterSummarySectionRepository;

    public AiChatMessagesInfo getMessages(UUID userId, Long beforeMessageId, int size) {
        validateUserId(userId);

        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        List<AiChatMessage> fetched = aiChatMessagePersistenceService.getMessages(userId, beforeMessageId, pageSize);

        boolean hasNext = fetched.size() > pageSize;
        if (hasNext) {
            fetched = fetched.subList(0, pageSize);
        }

        Long nextCursor = hasNext && !fetched.isEmpty()
                ? fetched.get(fetched.size() - 1).getMessageId()
                :null;

        List<AiChatInfo> messages = fetched.stream()
                .sorted(Comparator.comparing(AiChatMessage::getMessageId))
                .map(this::toInfo)
                .toList();

        return new AiChatMessagesInfo(messages, nextCursor, hasNext);
    }

    public AiChatInfo sendMessage(AiChatCommand command, UUID userId) {
        validateUserId(userId);
        validateCommand(command);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<AiChatMessage> history = aiChatMessagePersistenceService.getRecentHistory(userId);

        String newsContext = buildNewsContext(command.newsClusterId());

        String answer = openAiChatClient.ask(history, command.message(), newsContext);

        aiChatMessagePersistenceService.saveUserMessage(user, command.message());
        AiChatMessage aiMessage = aiChatMessagePersistenceService.saveAiMessage(user, answer);

        try {
            questProgressService.handleEvent(userId, QuestEventType.USE_AI_CHAT);
        } catch (RuntimeException e) {
            log.warn("퀘스트 진행도 반영 실패 userId={}", userId, e);
        }

        return toInfo(aiMessage);
    }

    private String buildNewsContext(Long newsClusterId) {
        if (newsClusterId == null) {
            return null;
        }

        NewsCluster cluster = newsClusterRepository.findByIdAndStatusAndSummaryStatusIn(
                        newsClusterId,
                        NewsCluster.ClusterStatus.ACTIVE,
                        AVAILABLE_SUMMARY_STATUSES
                )
                .orElse(null);

        if (cluster == null) {
            return null;
        }

        List<ClusterSummarySection> sections =
                clusterSummarySectionRepository.findByNewsClusterIdOrderBySectionOrderAsc(newsClusterId);

        String sectionText = sections.stream()
                .map(section -> "- " + section.getHeading() + ": " + section.getBody())
                .collect(Collectors.joining("\n"));

        String context = """
                [NEWS_CONTEXT]
                Title: %s
                Summary: %s
                Sections:
                %s
                """.formatted(
                cluster.getTitle(),
                cluster.getSummary(),
                sectionText.isBlank() ? "(no section summary)" : sectionText
        );

        return truncate(context, MAX_NEWS_CONTEXT_CHARS);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }

        int endIndex = Math.max(0, maxLength - TRUNCATED_SUFFIX.length());
        return value.substring(0, endIndex) + TRUNCATED_SUFFIX;
    }
    private void validateUserId(UUID userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }

    private void validateCommand(AiChatCommand command) {
        if (command == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        String message = command.message();

        if (message == null || message.isBlank()) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_EMPTY);
        }

        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_TOO_LONG);
        }
    }

    private AiChatInfo toInfo(AiChatMessage message) {
        return new AiChatInfo(
                message.getMessageId(),
                message.getUser().getUserId(),
                message.getRole().name(),
                message.getContent(),
                message.getCreatedAt(),
                AiChatAnswerParser.parse(message.getRole().name(), message.getContent())
        );
    }
}
