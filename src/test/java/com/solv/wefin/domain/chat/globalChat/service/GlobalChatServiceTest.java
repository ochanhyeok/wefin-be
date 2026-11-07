package com.solv.wefin.domain.chat.globalChat.service;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.chat.common.constant.ChatScope;
import com.solv.wefin.domain.chat.common.service.ChatSpamGuard;
import com.solv.wefin.domain.chat.globalChat.dto.command.GlobalProfitShareCommand;
import com.solv.wefin.domain.chat.globalChat.dto.info.GlobalChatMessagesInfo;
import com.solv.wefin.domain.chat.globalChat.entity.ChatRole;
import com.solv.wefin.domain.chat.globalChat.entity.GlobalChatMessage;
import com.solv.wefin.domain.chat.globalChat.event.GlobalChatMessageCreatedEvent;
import com.solv.wefin.domain.chat.globalChat.repository.GlobalChatMessageRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class GlobalChatServiceTest {

    private ApplicationEventPublisher eventPublisher;
    private GlobalChatMessageRepository globalChatMessageRepository;
    private UserRepository userRepository;
    private GlobalChatService globalChatService;
    private ChatSpamGuard chatSpamGuard;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        globalChatMessageRepository = mock(GlobalChatMessageRepository.class);
        userRepository = mock(UserRepository.class);
        chatSpamGuard = mock(ChatSpamGuard.class);

        globalChatService = new GlobalChatService(
                eventPublisher,
                globalChatMessageRepository,
                userRepository,
                chatSpamGuard
        );
    }

    @Test
    @DisplayName("Send message publishes event")
    void sendMessage_success() {
        // given
        UUID userId = UUID.randomUUID();
        String content = "hello";

        User user = User.createNormalAccount("test1@test.com", "testUser", "password1");
        ReflectionTestUtils.setField(user, "userId", userId);

        GlobalChatMessage savedMessage = GlobalChatMessage.builder()
                .user(user)
                .role(ChatRole.USER)
                .content(content)
                .createdAt(OffsetDateTime.now())
                .build();
        ReflectionTestUtils.setField(savedMessage, "id", 1L);

        when(globalChatMessageRepository.countByUser_UserIdAndCreatedAtAfter(
                eq(userId), any(OffsetDateTime.class))
        ).thenReturn(0L);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(globalChatMessageRepository.save(any(GlobalChatMessage.class))).thenReturn(savedMessage);

        // when
        globalChatService.sendMessage(content, userId);

        // then
        verify(globalChatMessageRepository, times(1))
                .countByUser_UserIdAndCreatedAtAfter(eq(userId), any(OffsetDateTime.class));
        verify(chatSpamGuard, times(1))
                .validate(startsWith(ChatScope.GLOBAL + ":"), eq(0L), any(OffsetDateTime.class));
        verify(userRepository, times(1)).findById(userId);
        verify(globalChatMessageRepository, times(1)).save(any(GlobalChatMessage.class));
        verify(eventPublisher, times(1)).publishEvent(any(GlobalChatMessageCreatedEvent.class));
    }

    @Test
    @DisplayName("Blank message throws exception")
    void sendMessage_fail_blank() {
        // given
        UUID userId = UUID.randomUUID();

        // when // then
        assertThrows(BusinessException.class,
                () -> globalChatService.sendMessage(" ", userId));
    }

    @Test
    @DisplayName("Too long message throws exception")
    void sendMessage_fail_tooLong() {
        // given
        UUID userId = UUID.randomUUID();
        String longMessage = "a".repeat(1001);

        // when // then
        assertThrows(BusinessException.class,
                () -> globalChatService.sendMessage(longMessage, userId));
    }

    @Test
    @DisplayName("Unknown user throws USER_NOT_FOUND")
    void sendMessage_fail_userNotFound() {
        // given
        UUID userId = UUID.randomUUID();
        String content = "hello";

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // when
        assertThrows(BusinessException.class,
                () -> globalChatService.sendMessage(content, userId));

        // then
        verify(globalChatMessageRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(GlobalChatMessageCreatedEvent.class));
    }

    @Test
    @DisplayName("Get messages returns paged info")
    void getMessages_success() {
        // given
        UUID userId = UUID.randomUUID();

        User user = User.createNormalAccount("test1@test.com", "testUser1", "password1");
        ReflectionTestUtils.setField(user, "userId", userId);

        GlobalChatMessage message = GlobalChatMessage.builder()
                .user(user)
                .role(ChatRole.USER)
                .content("recent")
                .createdAt(OffsetDateTime.now())
                .build();
        ReflectionTestUtils.setField(message, "id", 10L);

        when(globalChatMessageRepository.findMessages(any(Pageable.class)))
                .thenReturn(List.of(message));

        // when
        GlobalChatMessagesInfo result = globalChatService.getMessages(null, 30);

        // then
        assertEquals(1, result.messages().size());
        assertEquals("testUser1", result.messages().get(0).sender());
        assertEquals("USER", result.messages().get(0).role());
        assertFalse(result.hasNext());
        assertNull(result.nextCursor());
    }

    @Test
    @DisplayName("Profit share stores system message and publishes event")
    void sendProfitShareMessage_profit_success() {
        // given
        GlobalProfitShareCommand command = GlobalProfitShareCommand.builder()
                .type("PROFIT_ALERT")
                .userNickname("tico")
                .stockName("samsung")
                .profitAmount(523000L)
                .build();

        GlobalChatMessage savedMessage = GlobalChatMessage.builder()
                .user(null)
                .role(ChatRole.SYSTEM)
                .content("system message")
                .createdAt(OffsetDateTime.now())
                .build();
        ReflectionTestUtils.setField(savedMessage, "id", 1L);

        when(globalChatMessageRepository.save(any(GlobalChatMessage.class)))
                .thenReturn(savedMessage);

        ArgumentCaptor<GlobalChatMessage> captor = ArgumentCaptor.forClass(GlobalChatMessage.class);

        // when
        globalChatService.sendProfitShareMessage(command);

        // then
        verify(globalChatMessageRepository).save(captor.capture());
        verify(eventPublisher).publishEvent(any(GlobalChatMessageCreatedEvent.class));

        GlobalChatMessage capturedMessage = captor.getValue();
        assertEquals(ChatRole.SYSTEM, capturedMessage.getRole());
        assertTrue(capturedMessage.getContent().contains("tico"));
        assertTrue(capturedMessage.getContent().contains("523,000"));
        assertNull(capturedMessage.getUser());
    }

    @Test
    @DisplayName("Zero profit amount throws exception")
    void sendProfitShareMessage_fail_when_profitAmount_zero() {
        // given
        GlobalProfitShareCommand command = GlobalProfitShareCommand.builder()
                .type("PROFIT_ALERT")
                .userNickname("tico")
                .stockName("samsung")
                .profitAmount(0L)
                .build();

        // when
        BusinessException exception = assertThrows(BusinessException.class,
                () -> globalChatService.sendProfitShareMessage(command));

        // then
        assertEquals(ErrorCode.INVALID_PROFIT_AMOUNT, exception.getErrorCode());
        verify(globalChatMessageRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("전체 채팅 메시지 조회 시 hasNext와 nextCursor를 계산한다")
    void getMessages_success_with_hasNext_and_nextCursor() {
        // given
        UUID userId = UUID.randomUUID();

        User user = User.createNormalAccount("test1@test.com", "testUser1", "password1");
        ReflectionTestUtils.setField(user, "userId", userId);

        GlobalChatMessage latestMessage = GlobalChatMessage.builder()
                .user(user)
                .role(ChatRole.USER)
                .content("세 번째 메시지")
                .createdAt(OffsetDateTime.now())
                .build();
        ReflectionTestUtils.setField(latestMessage, "id", 3L);

        GlobalChatMessage middleMessage = GlobalChatMessage.builder()
                .user(user)
                .role(ChatRole.USER)
                .content("두 번째 메시지")
                .createdAt(OffsetDateTime.now().minusMinutes(1))
                .build();
        ReflectionTestUtils.setField(middleMessage, "id", 2L);

        GlobalChatMessage oldestMessage = GlobalChatMessage.builder()
                .user(user)
                .role(ChatRole.USER)
                .content("첫 번째 메시지")
                .createdAt(OffsetDateTime.now().minusMinutes(2))
                .build();
        ReflectionTestUtils.setField(oldestMessage, "id", 1L);

        when(globalChatMessageRepository.findMessages(any(Pageable.class)))
                .thenReturn(List.of(latestMessage, middleMessage, oldestMessage));

        // when
        GlobalChatMessagesInfo result = globalChatService.getMessages(null, 2);

        // then
        assertEquals(2, result.messages().size());
        assertTrue(result.hasNext());
        assertEquals(2L, result.nextCursor());

        assertEquals(2L, result.messages().get(0).messageId());
        assertEquals("두 번째 메시지", result.messages().get(0).content());
        assertEquals(3L, result.messages().get(1).messageId());
        assertEquals("세 번째 메시지", result.messages().get(1).content());
    }

}
