package com.solv.wefin.domain.chat.groupChat.service;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.chat.aiChat.client.OpenAiChatClient;
import com.solv.wefin.domain.chat.common.constant.ChatScope;
import com.solv.wefin.domain.chat.common.service.ChatSpamGuard;
import com.solv.wefin.domain.chat.groupChat.dto.command.ShareNewsCommand;
import com.solv.wefin.domain.chat.groupChat.dto.info.ChatMessagesInfo;
import com.solv.wefin.domain.chat.groupChat.entity.ChatMessage;
import com.solv.wefin.domain.chat.groupChat.entity.ChatMessageNewsShare;
import com.solv.wefin.domain.chat.groupChat.entity.MessageType;
import com.solv.wefin.domain.chat.groupChat.event.ChatMessageCreatedEvent;
import com.solv.wefin.domain.chat.groupChat.repository.ChatMessageRepository;
import com.solv.wefin.domain.group.entity.Group;
import com.solv.wefin.domain.group.entity.GroupMember;
import com.solv.wefin.domain.group.repository.GroupMemberRepository;
import com.solv.wefin.domain.news.cluster.entity.NewsCluster;
import com.solv.wefin.domain.news.cluster.repository.NewsClusterRepository;
import com.solv.wefin.domain.quest.entity.QuestEventType;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.domain.vote.repository.VoteOptionRepository;
import com.solv.wefin.domain.vote.repository.VoteRepository;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageServiceTest {

    private ChatMessageRepository chatMessageRepository;
    private UserRepository userRepository;
    private ApplicationEventPublisher eventPublisher;
    private GroupMemberRepository groupMemberRepository;
    private ChatSpamGuard chatSpamGuard;
    private ChatMessageService chatMessageService;
    private NewsClusterRepository newsClusterRepository;
    private ChatMessageNewsShareService chatMessageNewsShareService;
    private QuestProgressService questProgressService;
    private VoteRepository voteRepository;
    private VoteOptionRepository voteOptionRepository;
    private OpenAiChatClient openAiChatClient;

    @BeforeEach
    void setUp() {
        chatMessageRepository = mock(ChatMessageRepository.class);
        userRepository = mock(UserRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        groupMemberRepository = mock(GroupMemberRepository.class);
        chatSpamGuard = mock(ChatSpamGuard.class);
        newsClusterRepository = mock(NewsClusterRepository.class);
        chatMessageNewsShareService = mock(ChatMessageNewsShareService.class);
        questProgressService = mock(QuestProgressService.class);
        voteRepository = mock(VoteRepository.class);
        voteOptionRepository = mock(VoteOptionRepository.class);
        openAiChatClient = mock(OpenAiChatClient.class);

        ChatMessageWriteService chatMessageWriteService = new ChatMessageWriteService(
                chatMessageRepository,
                eventPublisher
        );

        chatMessageService = new ChatMessageService(
                chatMessageRepository,
                userRepository,
                eventPublisher,
                groupMemberRepository,
                chatSpamGuard,
                questProgressService,
                voteRepository,
                voteOptionRepository,
                openAiChatClient,
                chatMessageWriteService,
                newsClusterRepository,
                chatMessageNewsShareService
        );
    }

    @Test
    @DisplayName("메시지 전송 시 이벤트 발행과 퀘스트 진행도가 반영된다")
    void sendMessage_success() {
        // given
        UUID userId = UUID.randomUUID();
        String content = "hello";

        User user = User.createNormalAccount("test@test.com", "groupUser", "password");
        ReflectionTestUtils.setField(user, "userId", userId);

        Group group = Group.builder().name("group-1").build();
        ReflectionTestUtils.setField(group, "id", 1L);

        GroupMember groupMember = GroupMember.builder()
                .user(user)
                .group(group)
                .role(GroupMember.GroupMemberRole.MEMBER)
                .status(GroupMember.GroupMemberStatus.ACTIVE)
                .build();

        ChatMessage savedMessage = ChatMessage.builder()
                .user(user)
                .group(group)
                .messageType(MessageType.CHAT)
                .content(content)
                .createdAt(OffsetDateTime.now())
                .build();
        ReflectionTestUtils.setField(savedMessage, "id", 10L);

        when(groupMemberRepository.findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE))
                .thenReturn(Optional.of(groupMember));
        when(chatMessageRepository.countByGroup_IdAndUser_UserIdAndCreatedAtAfter(
                eq(1L), eq(userId), any(OffsetDateTime.class))
        ).thenReturn(0L);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(savedMessage);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);

        // when
        chatMessageService.sendMessage(content, userId, null);

        // then
        verify(chatMessageRepository).countByGroup_IdAndUser_UserIdAndCreatedAtAfter(
                eq(1L), eq(userId), any(OffsetDateTime.class)
        );
        verify(chatSpamGuard).validate(eq(ChatScope.groupKey(1L, userId)), eq(0L), any(OffsetDateTime.class));
        verify(chatMessageRepository).save(captor.capture());
        verify(eventPublisher).publishEvent(any(ChatMessageCreatedEvent.class));
        verify(questProgressService).handleEvent(userId, QuestEventType.SEND_GROUP_CHAT);

        ChatMessage capturedMessage = captor.getValue();
        assertEquals(group, capturedMessage.getGroup());
        assertEquals(user, capturedMessage.getUser());
        assertEquals(MessageType.CHAT, capturedMessage.getMessageType());
        assertEquals(content, capturedMessage.getContent());
        assertNull(capturedMessage.getReplyToMessage());
    }

    @Test
    @DisplayName("빈 메시지를 전송하면 예외가 발생한다")
    void sendMessage_fail_blank() {
        // given
        UUID userId = UUID.randomUUID();

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> chatMessageService.sendMessage(" ", userId, null)
        );

        // then
        assertEquals(ErrorCode.CHAT_MESSAGE_EMPTY, exception.getErrorCode());
        verify(chatMessageRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("/영 명령어를 전송하면 사용자 메시지와 시스템 메시지가 함께 저장된다")
    void sendMessage_youngCommand_success() {
        // given
        UUID userId = UUID.randomUUID();

        User user = User.createNormalAccount("test@test.com", "groupUser", "password");
        ReflectionTestUtils.setField(user, "userId", userId);

        Group group = Group.builder().name("group-1").build();
        ReflectionTestUtils.setField(group, "id", 1L);

        GroupMember groupMember = GroupMember.builder()
                .user(user)
                .group(group)
                .role(GroupMember.GroupMemberRole.MEMBER)
                .status(GroupMember.GroupMemberStatus.ACTIVE)
                .build();

        ChatMessage savedUserMessage = ChatMessage.builder()
                .user(user)
                .group(group)
                .messageType(MessageType.CHAT)
                .content("영")
                .createdAt(OffsetDateTime.now())
                .build();
        ReflectionTestUtils.setField(savedUserMessage, "id", 22L);

        ChatMessage savedSystemMessage = ChatMessage.builder()
                .group(group)
                .messageType(MessageType.SYSTEM)
                .content("차")
                .createdAt(OffsetDateTime.now())
                .build();
        ReflectionTestUtils.setField(savedSystemMessage, "id", 23L);

        when(groupMemberRepository.findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE))
                .thenReturn(Optional.of(groupMember));
        when(chatMessageRepository.countByGroup_IdAndUser_UserIdAndCreatedAtAfter(
                eq(1L), eq(userId), any(OffsetDateTime.class))
        ).thenReturn(0L);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(chatMessageRepository.save(any(ChatMessage.class)))
                .thenReturn(savedUserMessage, savedSystemMessage);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);

        // when
        chatMessageService.sendMessage("/영", userId, null);

        // then
        verify(openAiChatClient, never()).ask(any(), any(), any());
        verify(chatMessageRepository, times(2)).save(captor.capture());
        verify(eventPublisher, times(2)).publishEvent(any(ChatMessageCreatedEvent.class));
        verify(questProgressService).handleEvent(userId, QuestEventType.SEND_GROUP_CHAT);

        List<ChatMessage> capturedMessages = captor.getAllValues();
        assertEquals(MessageType.CHAT, capturedMessages.get(0).getMessageType());
        assertEquals("영", capturedMessages.get(0).getContent());
        assertEquals(MessageType.SYSTEM, capturedMessages.get(1).getMessageType());
        assertEquals("차", capturedMessages.get(1).getContent());
        assertNull(capturedMessages.get(1).getUser());
    }

    @Test
    @DisplayName("/wefini AI 호출이 실패해도 질문 메시지는 남고 안내 시스템 메시지가 발행된다")
    void sendMessage_wefiniCommand_aiFailure_keepsQuestionAndPublishesFallback() {
        // given
        UUID userId = UUID.randomUUID();

        User user = User.createNormalAccount("test@test.com", "groupUser", "password");
        ReflectionTestUtils.setField(user, "userId", userId);

        Group group = Group.builder().name("group-1").build();
        ReflectionTestUtils.setField(group, "id", 1L);

        GroupMember groupMember = GroupMember.builder()
                .user(user)
                .group(group)
                .role(GroupMember.GroupMemberRole.MEMBER)
                .status(GroupMember.GroupMemberStatus.ACTIVE)
                .build();

        ChatMessage savedUserMessage = ChatMessage.builder()
                .user(user)
                .group(group)
                .messageType(MessageType.CHAT)
                .content("/wefini 질문")
                .createdAt(OffsetDateTime.now())
                .build();
        ReflectionTestUtils.setField(savedUserMessage, "id", 30L);

        ChatMessage savedSystemMessage = ChatMessage.builder()
                .group(group)
                .messageType(MessageType.SYSTEM)
                .content("AI 응답을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.")
                .createdAt(OffsetDateTime.now())
                .build();
        ReflectionTestUtils.setField(savedSystemMessage, "id", 31L);

        when(groupMemberRepository.findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE))
                .thenReturn(Optional.of(groupMember));
        when(chatMessageRepository.countByGroup_IdAndUser_UserIdAndCreatedAtAfter(
                eq(1L), eq(userId), any(OffsetDateTime.class))
        ).thenReturn(0L);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(openAiChatClient.ask(any(), eq("질문"), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_CHAT_REQUEST_FAILED));
        when(chatMessageRepository.save(any(ChatMessage.class)))
                .thenReturn(savedUserMessage, savedSystemMessage);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);

        // when
        chatMessageService.sendMessage("  /wefini 질문  ", userId, null);

        // then
        verify(chatMessageRepository, times(2)).save(captor.capture());
        verify(eventPublisher, times(2)).publishEvent(any(ChatMessageCreatedEvent.class));
        verify(questProgressService, never()).handleEvent(userId, QuestEventType.USE_AI_CHAT);
        verify(questProgressService, never()).handleEvent(userId, QuestEventType.SEND_GROUP_CHAT);

        List<ChatMessage> capturedMessages = captor.getAllValues();
        assertEquals(MessageType.CHAT, capturedMessages.get(0).getMessageType());
        assertEquals("/wefini 질문", capturedMessages.get(0).getContent());
        assertEquals(MessageType.SYSTEM, capturedMessages.get(1).getMessageType());
        assertEquals(
                "AI 응답을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.",
                capturedMessages.get(1).getContent()
        );
        assertNull(capturedMessages.get(1).getUser());
    }

    @Test
    @DisplayName("메시지 조회 시 hasNext와 nextCursor를 올바르게 계산한다")
    void getMessages_success_with_hasNext_and_nextCursor() {
        // given
        UUID userId = UUID.randomUUID();

        User user = User.createNormalAccount("test@test.com", "groupUser", "password");
        ReflectionTestUtils.setField(user, "userId", userId);

        Group group = Group.builder().name("group-1").build();
        ReflectionTestUtils.setField(group, "id", 3L);

        GroupMember groupMember = GroupMember.builder()
                .user(user)
                .group(group)
                .role(GroupMember.GroupMemberRole.MEMBER)
                .status(GroupMember.GroupMemberStatus.ACTIVE)
                .build();

        ChatMessage latestMessage = ChatMessage.builder()
                .user(user)
                .group(group)
                .messageType(MessageType.CHAT)
                .content("third")
                .createdAt(OffsetDateTime.now())
                .build();
        ReflectionTestUtils.setField(latestMessage, "id", 3L);

        ChatMessage middleMessage = ChatMessage.builder()
                .user(user)
                .group(group)
                .messageType(MessageType.CHAT)
                .content("second")
                .createdAt(OffsetDateTime.now().minusMinutes(1))
                .build();
        ReflectionTestUtils.setField(middleMessage, "id", 2L);

        ChatMessage oldestMessage = ChatMessage.builder()
                .user(user)
                .group(group)
                .messageType(MessageType.CHAT)
                .content("first")
                .createdAt(OffsetDateTime.now().minusMinutes(2))
                .build();
        ReflectionTestUtils.setField(oldestMessage, "id", 1L);

        when(groupMemberRepository.findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE))
                .thenReturn(Optional.of(groupMember));
        when(chatMessageRepository.findMessagesByGroupId(eq(3L), any(Pageable.class)))
                .thenReturn(List.of(latestMessage, middleMessage, oldestMessage));

        // when
        ChatMessagesInfo result = chatMessageService.getMessages(userId, null, 2);

        // then
        assertEquals(2, result.messages().size());
        assertTrue(result.hasNext());
        assertEquals(2L, result.nextCursor());
        assertEquals(2L, result.messages().get(0).messageId());
        assertEquals("second", result.messages().get(0).content());
        assertEquals(3L, result.messages().get(1).messageId());
        assertEquals("third", result.messages().get(1).content());
    }

    @Test
    @DisplayName("뉴스 공유 시 뉴스 공유 정보가 포함된 응답을 반환한다")
    void shareNews_success() {
        // given
        UUID userId = UUID.randomUUID();

        User user = User.createNormalAccount("test@test.com", "groupUser", "password");
        ReflectionTestUtils.setField(user, "userId", userId);

        Group group = Group.builder().name("group").build();
        ReflectionTestUtils.setField(group, "id", 1L);

        GroupMember groupMember = GroupMember.builder()
                .user(user)
                .group(group)
                .role(GroupMember.GroupMemberRole.MEMBER)
                .status(GroupMember.GroupMemberStatus.ACTIVE)
                .build();

        NewsCluster newsCluster = NewsCluster.createSingle(
                new float[]{1.0f},
                100L,
                "https://image.test/thumb.png",
                OffsetDateTime.now()
        );
        ReflectionTestUtils.setField(newsCluster, "id", 55L);
        ReflectionTestUtils.setField(newsCluster, "title", "cluster title");
        ReflectionTestUtils.setField(newsCluster, "summary", "cluster summary");
        ReflectionTestUtils.setField(newsCluster, "thumbnailUrl", "https://image.test/thumb.png");

        ChatMessage savedMessage = ChatMessage.builder()
                .user(user)
                .group(group)
                .messageType(MessageType.NEWS)
                .content("cluster title")
                .createdAt(OffsetDateTime.now())
                .build();
        ReflectionTestUtils.setField(savedMessage, "id", 10L);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(groupMemberRepository.findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE))
                .thenReturn(Optional.of(groupMember));
        when(newsClusterRepository.findById(55L)).thenReturn(Optional.of(newsCluster));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(savedMessage);
        when(chatMessageNewsShareService.save(savedMessage, newsCluster))
                .thenAnswer(invocation -> {
                    ChatMessageNewsShare newsShare = ChatMessageNewsShare.create(savedMessage, newsCluster);
                    savedMessage.attachNewsShare(newsShare);
                    return newsShare;
                });

        // when
        var result = chatMessageService.shareNews(userId, new ShareNewsCommand(55L));

        // then
        verify(chatMessageRepository).save(argThat(message ->
                message.getMessageType() == MessageType.NEWS
                        && "cluster title".equals(message.getContent())
                        && message.getReplyToMessage() == null
        ));
        verify(chatMessageNewsShareService).save(savedMessage, newsCluster);
        verify(eventPublisher).publishEvent(any(ChatMessageCreatedEvent.class));
        verify(questProgressService).handleEvent(userId, QuestEventType.SHARE_NEWS);
        verify(questProgressService).handleEvent(userId, QuestEventType.SEND_GROUP_CHAT);

        assertEquals(10L, result.messageId());
        assertEquals("NEWS", result.messageType());
        assertNotNull(result.newsShare());
        assertEquals(55L, result.newsShare().newsClusterId());
        assertEquals("cluster title", result.newsShare().title());
        assertEquals("cluster summary", result.newsShare().summary());
        assertEquals("https://image.test/thumb.png", result.newsShare().thumbnailUrl());
    }
}
