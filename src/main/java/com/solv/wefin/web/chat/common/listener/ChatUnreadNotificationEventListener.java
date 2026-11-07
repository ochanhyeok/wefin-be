package com.solv.wefin.web.chat.common.listener;

import com.solv.wefin.domain.chat.common.dto.info.ChatUnreadInfo;
import com.solv.wefin.domain.chat.common.service.ChatReadStateService;
import com.solv.wefin.domain.chat.groupChat.event.ChatMessageCreatedEvent;
import com.solv.wefin.domain.group.entity.GroupMember;
import com.solv.wefin.domain.group.repository.GroupMemberRepository;
import com.solv.wefin.web.chat.common.dto.response.ChatUnreadNotificationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatUnreadNotificationEventListener {

    private static final String USER_QUEUE_DESTINATION = "/queue/chat-unread";

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatReadStateService chatReadStateService;
    private final GroupMemberRepository groupMemberRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleGroupChatCreated(ChatMessageCreatedEvent event) {
        List<UUID> recipientIds = groupMemberRepository.findUserIdsByGroupIdAndStatus(
                event.getGroupId(),
                GroupMember.GroupMemberStatus.ACTIVE
        ).stream()
                .filter(userId -> !userId.equals(event.getMessage().userId()))
                .toList();

        for (UUID recipientId : recipientIds) {
            try {
                ChatUnreadInfo info = chatReadStateService.getUnreadInfoSnapshot(recipientId);
                sendUnreadNotification(
                        recipientId,
                        ChatUnreadNotificationResponse.of(
                                "GROUP",
                                event.getMessage().messageId(),
                                event.getGroupId(),
                                event.getMessage().sender(),
                                event.getMessage().content(),
                                info
                        )
                );
            } catch (RuntimeException e) {
                log.warn("chat unread snapshot failed userId={}", recipientId, e);
            }
        }
    }

    private void sendUnreadNotification(UUID recipientId, ChatUnreadNotificationResponse payload) {
        try {
            messagingTemplate.convertAndSendToUser(
                    recipientId.toString(),
                    USER_QUEUE_DESTINATION,
                    payload
            );
        } catch (RuntimeException e) {
            log.warn("chat unread notification send failed userId={}", recipientId, e);
        }
    }
}
