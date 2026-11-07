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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatServiceTest {

    private AiChatMessagePersistenceService aiChatMessagePersistenceService;
    private UserRepository userRepository;
    private OpenAiChatClient openAiChatClient;
    private QuestProgressService questProgressService;
    private NewsClusterRepository newsClusterRepository;
    private ClusterSummarySectionRepository clusterSummarySectionRepository;
    private AiChatService aiChatService;

    @BeforeEach
    void setUp() {
        aiChatMessagePersistenceService = mock(AiChatMessagePersistenceService.class);
        userRepository = mock(UserRepository.class);
        openAiChatClient = mock(OpenAiChatClient.class);
        questProgressService = mock(QuestProgressService.class);
        newsClusterRepository = mock(NewsClusterRepository.class);
        clusterSummarySectionRepository = mock(ClusterSummarySectionRepository.class);

        aiChatService = new AiChatService(
                openAiChatClient,
                aiChatMessagePersistenceService,
                userRepository,
                questProgressService,
                newsClusterRepository,
                clusterSummarySectionRepository
        );
    }

    @Test
    @DisplayName("현재 사용자의 AI 채팅 메시지 목록을 반환한다")
    void getMessages_success() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);

        AiChatMessage firstMessage = AiChatMessage.createUserMessage(user, "삼성전자 어때?");
        ReflectionTestUtils.setField(firstMessage, "messageId", 1L);
        ReflectionTestUtils.setField(firstMessage, "createdAt", OffsetDateTime.now().minusMinutes(1));

        AiChatMessage secondMessage = AiChatMessage.createAiMessage(user, "최근 실적 기준으로 설명드릴게요.");
        ReflectionTestUtils.setField(secondMessage, "messageId", 2L);
        ReflectionTestUtils.setField(secondMessage, "createdAt", OffsetDateTime.now());

        when(aiChatMessagePersistenceService.getMessages(userId, null, 30))
                .thenReturn(List.of(secondMessage, firstMessage));

        // when
        AiChatMessagesInfo result = aiChatService.getMessages(userId, null, 30);

        // then
        assertEquals(2, result.messages().size());
        assertEquals("USER", result.messages().get(0).role());
        assertEquals("삼성전자 어때?", result.messages().get(0).content());
        assertEquals("AI", result.messages().get(1).role());
        assertEquals("최근 실적 기준으로 설명드릴게요.", result.messages().get(1).content());
        assertEquals(false, result.hasNext());
        assertEquals(null, result.nextCursor());
    }

    @Test
    @DisplayName("과거 history와 현재 질문을 분리해 AI에 전달하고 사용자와 AI 메시지를 저장한다")
    void sendMessage_success() {
        // given
        UUID userId = UUID.randomUUID();
        AiChatCommand command = new AiChatCommand("삼성전자 전망 알려줘", null);
        User user = createUser(userId);

        AiChatMessage historyUserMessage = AiChatMessage.createUserMessage(user, "이전 대화 질문");
        ReflectionTestUtils.setField(historyUserMessage, "messageId", 1L);
        ReflectionTestUtils.setField(historyUserMessage, "createdAt", OffsetDateTime.now().minusSeconds(10));

        AiChatMessage savedUserMessage = AiChatMessage.createUserMessage(user, command.message());
        ReflectionTestUtils.setField(savedUserMessage, "messageId", 2L);
        ReflectionTestUtils.setField(savedUserMessage, "createdAt", OffsetDateTime.now().minusSeconds(5));

        AiChatMessage savedAiMessage = AiChatMessage.createAiMessage(user, "최근 실적 기준으로 설명드릴게요.");
        ReflectionTestUtils.setField(savedAiMessage, "messageId", 3L);
        ReflectionTestUtils.setField(savedAiMessage, "createdAt", OffsetDateTime.now());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(aiChatMessagePersistenceService.getRecentHistory(userId))
                .thenReturn(List.of(historyUserMessage));
        when(openAiChatClient.ask(anyList(), eq(command.message()), isNull()))
                .thenReturn("최근 실적 기준으로 설명드릴게요.");
        when(aiChatMessagePersistenceService.saveUserMessage(user, command.message()))
                .thenReturn(savedUserMessage);
        when(aiChatMessagePersistenceService.saveAiMessage(user, "최근 실적 기준으로 설명드릴게요."))
                .thenReturn(savedAiMessage);

        ArgumentCaptor<List<AiChatMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);

        // when
        AiChatInfo result = aiChatService.sendMessage(command, userId);

        // then
        verify(aiChatMessagePersistenceService).saveUserMessage(user, command.message());
        verify(aiChatMessagePersistenceService).saveAiMessage(user, "최근 실적 기준으로 설명드릴게요.");
        verify(openAiChatClient).ask(historyCaptor.capture(), eq(command.message()), isNull());
        verify(questProgressService).handleEvent(userId, QuestEventType.USE_AI_CHAT);

        List<AiChatMessage> history = historyCaptor.getValue();
        assertEquals(1, history.size());
        assertEquals("이전 대화 질문", history.get(0).getContent());

        assertEquals(3L, result.messageId());
        assertEquals(userId, result.userId());
        assertEquals("AI", result.role());
        assertEquals("최근 실적 기준으로 설명드릴게요.", result.content());
    }

    @Test
    @DisplayName("입력 메시지가 비어 있으면 예외가 발생한다")
    void sendMessage_fail_blank() {
        // given
        UUID userId = UUID.randomUUID();
        AiChatCommand command = new AiChatCommand(" ", null);

        // when
        BusinessException exception = assertThrows(BusinessException.class,
                () -> aiChatService.sendMessage(command, userId));

        // then
        assertEquals(ErrorCode.CHAT_MESSAGE_EMPTY, exception.getErrorCode());
    }

    @Test
    @DisplayName("입력 메시지가 최대 길이를 초과하면 예외가 발생한다")
    void sendMessage_fail_too_long() {
        // given
        UUID userId = UUID.randomUUID();
        AiChatCommand command = new AiChatCommand("a".repeat(1001), null);

        // when
        BusinessException exception = assertThrows(BusinessException.class,
                () -> aiChatService.sendMessage(command, userId));

        // then
        assertEquals(ErrorCode.CHAT_MESSAGE_TOO_LONG, exception.getErrorCode());
    }

    @Test
    @DisplayName("command가 null이면 예외가 발생한다")
    void sendMessage_fail_null_command() {
        // given
        UUID userId = UUID.randomUUID();

        // when
        BusinessException exception = assertThrows(BusinessException.class,
                () -> aiChatService.sendMessage(null, userId));

        // then
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    @DisplayName("userId가 null이면 예외가 발생한다")
    void sendMessage_fail_null_userId() {
        // given
        AiChatCommand command = new AiChatCommand("질문", null);

        // when
        BusinessException exception = assertThrows(BusinessException.class,
                () -> aiChatService.sendMessage(command, null));

        // then
        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    @DisplayName("사용자를 찾을 수 없으면 예외가 발생한다")
    void sendMessage_fail_user_not_found() {
        // given
        UUID userId = UUID.randomUUID();
        AiChatCommand command = new AiChatCommand("질문", null);

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // when
        BusinessException exception = assertThrows(BusinessException.class,
                () -> aiChatService.sendMessage(command, userId));

        // then
        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    @DisplayName("AI 클라이언트 호출 실패 시 예외를 전파한다")
    void sendMessage_fail_when_client_throws() {
        // given
        UUID userId = UUID.randomUUID();
        AiChatCommand command = new AiChatCommand("질문", null);
        User user = createUser(userId);

        AiChatMessage historyUserMessage = AiChatMessage.createUserMessage(user, "이전 질문");
        ReflectionTestUtils.setField(historyUserMessage, "messageId", 1L);
        ReflectionTestUtils.setField(historyUserMessage, "createdAt", OffsetDateTime.now());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(aiChatMessagePersistenceService.getRecentHistory(userId))
                .thenReturn(List.of(historyUserMessage));
        when(openAiChatClient.ask(anyList(), eq(command.message()), isNull()))
                .thenThrow(new BusinessException(ErrorCode.AI_CHAT_REQUEST_FAILED));

        // when
        BusinessException exception = assertThrows(BusinessException.class,
                () -> aiChatService.sendMessage(command, userId));

        // then
        assertEquals(ErrorCode.AI_CHAT_REQUEST_FAILED, exception.getErrorCode());
    }

    @Test
    @DisplayName("AI 응답 시간이 초과되면 예외를 전파한다")
    void sendMessage_fail_timeout() {
        // given
        UUID userId = UUID.randomUUID();
        AiChatCommand command = new AiChatCommand("질문", null);
        User user = createUser(userId);

        AiChatMessage historyUserMessage = AiChatMessage.createUserMessage(user, "이전 질문");
        ReflectionTestUtils.setField(historyUserMessage, "messageId", 1L);
        ReflectionTestUtils.setField(historyUserMessage, "createdAt", OffsetDateTime.now());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(aiChatMessagePersistenceService.getRecentHistory(userId))
                .thenReturn(List.of(historyUserMessage));
        when(openAiChatClient.ask(anyList(), eq(command.message()), isNull()))
                .thenThrow(new BusinessException(ErrorCode.AI_CHAT_TIMEOUT));

        // when
        BusinessException exception = assertThrows(BusinessException.class,
                () -> aiChatService.sendMessage(command, userId));

        // then
        assertEquals(ErrorCode.AI_CHAT_TIMEOUT, exception.getErrorCode());
    }

    @Test
    @DisplayName("유효하지 않거나 비활성 뉴스 클러스터여도 뉴스 컨텍스트 없이 AI 답변을 생성한다")
    void sendMessage_success_without_news_context_when_cluster_is_not_available() {
        // given
        UUID userId = UUID.randomUUID();
        Long newsClusterId = 999L;
        AiChatCommand command = new AiChatCommand("질문", newsClusterId);
        User user = createUser(userId);

        AiChatMessage historyUserMessage = AiChatMessage.createUserMessage(user, "이전 질문");
        ReflectionTestUtils.setField(historyUserMessage, "messageId", 1L);
        ReflectionTestUtils.setField(historyUserMessage, "createdAt", OffsetDateTime.now().minusSeconds(10));

        AiChatMessage savedUserMessage = AiChatMessage.createUserMessage(user, command.message());
        ReflectionTestUtils.setField(savedUserMessage, "messageId", 2L);
        ReflectionTestUtils.setField(savedUserMessage, "createdAt", OffsetDateTime.now().minusSeconds(5));

        AiChatMessage savedAiMessage = AiChatMessage.createAiMessage(user, "뉴스 맥락 없이도 답변합니다.");
        ReflectionTestUtils.setField(savedAiMessage, "messageId", 3L);
        ReflectionTestUtils.setField(savedAiMessage, "createdAt", OffsetDateTime.now());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(aiChatMessagePersistenceService.getRecentHistory(userId))
                .thenReturn(List.of(historyUserMessage));
        when(newsClusterRepository.findByIdAndStatusAndSummaryStatusIn(
                eq(newsClusterId),
                eq(NewsCluster.ClusterStatus.ACTIVE),
                anyList()
        )).thenReturn(Optional.empty());
        when(openAiChatClient.ask(anyList(), eq(command.message()), isNull()))
                .thenReturn("뉴스 맥락 없이도 답변합니다.");
        when(aiChatMessagePersistenceService.saveUserMessage(user, command.message()))
                .thenReturn(savedUserMessage);
        when(aiChatMessagePersistenceService.saveAiMessage(user, "뉴스 맥락 없이도 답변합니다."))
                .thenReturn(savedAiMessage);

        // when
        AiChatInfo result = aiChatService.sendMessage(command, userId);

        // then
        verify(openAiChatClient).ask(anyList(), eq(command.message()), isNull());
        verify(clusterSummarySectionRepository, org.mockito.Mockito.never())
                .findByNewsClusterIdOrderBySectionOrderAsc(eq(newsClusterId));
        assertEquals("뉴스 맥락 없이도 답변합니다.", result.content());
    }

    @Test
    @DisplayName("유효한 newsClusterId가 있으면 뉴스 컨텍스트를 함께 전달한다")
    void sendMessage_success_with_news_context() {
        // given
        UUID userId = UUID.randomUUID();
        Long newsClusterId = 55L;
        AiChatCommand command = new AiChatCommand("질문", newsClusterId);
        User user = createUser(userId);

        AiChatMessage historyUserMessage = AiChatMessage.createUserMessage(user, "이전 질문");
        ReflectionTestUtils.setField(historyUserMessage, "messageId", 1L);
        ReflectionTestUtils.setField(historyUserMessage, "createdAt", OffsetDateTime.now().minusSeconds(10));

        AiChatMessage savedUserMessage = AiChatMessage.createUserMessage(user, command.message());
        ReflectionTestUtils.setField(savedUserMessage, "messageId", 2L);
        ReflectionTestUtils.setField(savedUserMessage, "createdAt", OffsetDateTime.now().minusSeconds(5));

        AiChatMessage savedAiMessage = AiChatMessage.createAiMessage(user, "뉴스 맥락을 참고한 답변입니다.");
        ReflectionTestUtils.setField(savedAiMessage, "messageId", 3L);
        ReflectionTestUtils.setField(savedAiMessage, "createdAt", OffsetDateTime.now());

        NewsCluster newsCluster = NewsCluster.createSingle(
                new float[]{1.0f},
                100L,
                "https://image.test/thumb.png",
                OffsetDateTime.now()
        );
        ReflectionTestUtils.setField(newsCluster, "id", newsClusterId);
        ReflectionTestUtils.setField(newsCluster, "title", "cluster title");
        ReflectionTestUtils.setField(newsCluster, "summary", "cluster summary");
        ReflectionTestUtils.setField(newsCluster, "status", NewsCluster.ClusterStatus.ACTIVE);
        ReflectionTestUtils.setField(newsCluster, "summaryStatus", NewsCluster.SummaryStatus.GENERATED);

        ClusterSummarySection firstSection = ClusterSummarySection.create(
                newsClusterId,
                0,
                "first heading",
                "first body"
        );
        ClusterSummarySection secondSection = ClusterSummarySection.create(
                newsClusterId,
                1,
                "second heading",
                "second body"
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(aiChatMessagePersistenceService.getRecentHistory(userId))
                .thenReturn(List.of(historyUserMessage));
        when(newsClusterRepository.findByIdAndStatusAndSummaryStatusIn(
                eq(newsClusterId),
                eq(NewsCluster.ClusterStatus.ACTIVE),
                anyList()
        )).thenReturn(Optional.of(newsCluster));
        when(clusterSummarySectionRepository.findByNewsClusterIdOrderBySectionOrderAsc(newsClusterId))
                .thenReturn(List.of(firstSection, secondSection));
        when(openAiChatClient.ask(anyList(), eq(command.message()), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("뉴스 맥락을 참고한 답변입니다.");
        when(aiChatMessagePersistenceService.saveUserMessage(user, command.message()))
                .thenReturn(savedUserMessage);
        when(aiChatMessagePersistenceService.saveAiMessage(user, "뉴스 맥락을 참고한 답변입니다."))
                .thenReturn(savedAiMessage);

        ArgumentCaptor<String> newsContextCaptor = ArgumentCaptor.forClass(String.class);

        // when
        AiChatInfo result = aiChatService.sendMessage(command, userId);

        // then
        verify(openAiChatClient).ask(anyList(), eq(command.message()), newsContextCaptor.capture());

        String newsContext = newsContextCaptor.getValue();
        assertEquals(true, newsContext.contains("Title: cluster title"));
        assertEquals(true, newsContext.contains("Summary: cluster summary"));
        assertEquals(true, newsContext.contains("- first heading: first body"));
        assertEquals(true, newsContext.contains("- second heading: second body"));
        assertEquals("뉴스 맥락을 참고한 답변입니다.", result.content());
    }

    private User createUser(UUID userId) {
        User user = User.createNormalAccount("test@test.com", "ai-user", "password");
        ReflectionTestUtils.setField(user, "userId", userId);
        return user;
    }

    @Test
    @DisplayName("AI 채팅 메시지 조회 시 hasNext와 nextCursor를 계산한다")
    void getMessages_success_with_hasNext_and_nextCursor() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);

        AiChatMessage latestMessage = AiChatMessage.createAiMessage(user, "세 번째 메시지");
        ReflectionTestUtils.setField(latestMessage, "messageId", 3L);
        ReflectionTestUtils.setField(latestMessage, "createdAt", OffsetDateTime.now());

        AiChatMessage middleMessage = AiChatMessage.createUserMessage(user, "두 번째 메시지");
        ReflectionTestUtils.setField(middleMessage, "messageId", 2L);
        ReflectionTestUtils.setField(middleMessage, "createdAt", OffsetDateTime.now().minusMinutes(1));

        AiChatMessage oldestMessage = AiChatMessage.createUserMessage(user, "첫 번째 메시지");
        ReflectionTestUtils.setField(oldestMessage, "messageId", 1L);
        ReflectionTestUtils.setField(oldestMessage, "createdAt", OffsetDateTime.now().minusMinutes(2));

        when(aiChatMessagePersistenceService.getMessages(userId, null, 2))
                .thenReturn(List.of(latestMessage, middleMessage, oldestMessage));

        // when
        AiChatMessagesInfo result = aiChatService.getMessages(userId, null, 2);

        // then
        assertEquals(2, result.messages().size());
        assertEquals(true, result.hasNext());
        assertEquals(2L, result.nextCursor());

        assertEquals(2L, result.messages().get(0).messageId());
        assertEquals("두 번째 메시지", result.messages().get(0).content());
        assertEquals(3L, result.messages().get(1).messageId());
        assertEquals("세 번째 메시지", result.messages().get(1).content());
    }
}
