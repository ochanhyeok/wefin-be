package com.solv.wefin.domain.chat.common.service;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.chat.common.dto.info.ChatUnreadInfo;
import com.solv.wefin.domain.chat.globalChat.repository.GlobalChatMessageRepository;
import com.solv.wefin.domain.chat.groupChat.repository.ChatMessageRepository;
import com.solv.wefin.domain.group.entity.Group;
import com.solv.wefin.domain.group.entity.GroupMember;
import com.solv.wefin.domain.group.repository.GroupMemberRepository;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ChatReadStateServiceTest {

    private UserRepository userRepository;
    private GroupMemberRepository groupMemberRepository;
    private GlobalChatMessageRepository globalChatMessageRepository;
    private ChatMessageRepository chatMessageRepository;
    private ChatReadStateService chatReadStateService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        groupMemberRepository = mock(GroupMemberRepository.class);
        globalChatMessageRepository = mock(GlobalChatMessageRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);

        chatReadStateService = new ChatReadStateService(
                userRepository,
                groupMemberRepository,
                globalChatMessageRepository,
                chatMessageRepository
        );
    }

    @Test
    @DisplayName("첫 unread 조회 시 읽음 기준이 없으면 현재 시점으로 초기화한다")
    void getUnreadInfo_success_initialize_read_state() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);
        GroupMember groupMember = createActiveGroupMember(user);

        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(groupMemberRepository.findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE))
                .thenReturn(Optional.of(groupMember));
        when(globalChatMessageRepository.findLatestMessageId()).thenReturn(15L);
        when(chatMessageRepository.findLatestMessageIdByGroupId(3L)).thenReturn(27L);

        // when
        ChatUnreadInfo result = chatReadStateService.getUnreadInfo(userId);

        // then
        assertEquals(0L, result.globalUnreadCount());
        assertEquals(0L, result.groupUnreadCount());
        assertEquals(15L, user.getLastReadGlobalMessageId());
        assertEquals(27L, groupMember.getLastReadChatMessageId());
        assertEquals(15L, result.lastReadGlobalMessageId());
        assertEquals(27L, result.lastReadGroupMessageId());
    }

    @Test
    @DisplayName("읽음 기준이 있으면 unread 개수를 계산한다")
    void getUnreadInfo_success_count_unread() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);
        ReflectionTestUtils.setField(user, "lastReadGlobalMessageId", 10L);

        GroupMember groupMember = createActiveGroupMember(user);
        ReflectionTestUtils.setField(groupMember, "lastReadChatMessageId", 20L);

        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(groupMemberRepository.findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE))
                .thenReturn(Optional.of(groupMember));
        when(globalChatMessageRepository.countUnreadAfterMessageId(10L, userId)).thenReturn(2L);
        when(chatMessageRepository.countUnreadAfterMessageId(3L, 20L, userId)).thenReturn(4L);

        // when
        ChatUnreadInfo result = chatReadStateService.getUnreadInfo(userId);

        // then
        assertEquals(2L, result.globalUnreadCount());
        assertEquals(4L, result.groupUnreadCount());
        assertEquals(6L, result.totalUnreadCount());
        assertEquals(10L, result.lastReadGlobalMessageId());
        assertEquals(20L, result.lastReadGroupMessageId());
    }

    @Test
    @DisplayName("내가 보낸 메시지는 unread 개수에서 제외한다")
    void getUnreadInfo_success_excludes_my_messages() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);
        ReflectionTestUtils.setField(user, "lastReadGlobalMessageId", 40L);

        GroupMember groupMember = createActiveGroupMember(user);
        ReflectionTestUtils.setField(groupMember, "lastReadChatMessageId", 50L);

        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(groupMemberRepository.findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE))
                .thenReturn(Optional.of(groupMember));
        when(globalChatMessageRepository.countUnreadAfterMessageId(40L, userId)).thenReturn(0L);
        when(chatMessageRepository.countUnreadAfterMessageId(3L, 50L, userId)).thenReturn(0L);

        // when
        ChatUnreadInfo result = chatReadStateService.getUnreadInfo(userId);

        // then
        assertEquals(0L, result.globalUnreadCount());
        assertEquals(0L, result.groupUnreadCount());
        assertEquals(40L, result.lastReadGlobalMessageId());
        assertEquals(50L, result.lastReadGroupMessageId());
    }

    @Test
    @DisplayName("전체 채팅 읽음 처리 시 최신 메시지 id로 갱신한다")
    void markGlobalChatRead_success() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);
        ReflectionTestUtils.setField(user, "lastReadGlobalMessageId", 8L);

        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(globalChatMessageRepository.findLatestMessageId()).thenReturn(12L);

        // when
        chatReadStateService.markGlobalChatRead(userId);

        // then
        assertEquals(12L, user.getLastReadGlobalMessageId());
    }

    @Test
    @DisplayName("그룹 채팅 읽음 처리 시 최신 메시지 id로 갱신한다")
    void markGroupChatRead_success() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);
        GroupMember groupMember = createActiveGroupMember(user);
        ReflectionTestUtils.setField(groupMember, "lastReadChatMessageId", 19L);

        when(groupMemberRepository.findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE))
                .thenReturn(Optional.of(groupMember));
        when(chatMessageRepository.findLatestMessageIdByGroupId(3L)).thenReturn(30L);

        // when
        chatReadStateService.markGroupChatRead(userId);

        // then
        assertEquals(30L, groupMember.getLastReadChatMessageId());
    }

    @Test
    @DisplayName("활성 그룹 멤버가 없으면 GROUP_MEMBER_FORBIDDEN 예외를 던진다")
    void getUnreadInfo_fail_when_group_member_missing() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);

        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(groupMemberRepository.findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE))
                .thenReturn(Optional.empty());

        // when
        BusinessException exception = assertThrows(BusinessException.class,
                () -> chatReadStateService.getUnreadInfo(userId));

        // then
        assertEquals(ErrorCode.GROUP_MEMBER_FORBIDDEN, exception.getErrorCode());
    }

    @Test
    @DisplayName("snapshot 조회는 그룹 미가입자여도 글로벌 unread 정보는 반환한다")
    void getUnreadInfoSnapshot_success_without_group_member() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);
        ReflectionTestUtils.setField(user, "lastReadGlobalMessageId", 12L);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(groupMemberRepository.findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(globalChatMessageRepository.countUnreadAfterMessageId(12L, userId)).thenReturn(3L);

        // when
        ChatUnreadInfo result = chatReadStateService.getUnreadInfoSnapshot(userId);

        // then
        assertEquals(3L, result.globalUnreadCount());
        assertEquals(0L, result.groupUnreadCount());
        assertEquals(12L, result.lastReadGlobalMessageId());
        assertNull(result.lastReadGroupMessageId());
        verify(chatMessageRepository, never()).countUnreadAfterMessageId(anyLong(), anyLong(), any());
    }

    private User createUser(UUID userId) {
        User user = User.createNormalAccount("read@test.com", "reader", "password");
        ReflectionTestUtils.setField(user, "userId", userId);
        return user;
    }

    private GroupMember createActiveGroupMember(User user) {
        Group group = Group.builder()
                .name("테스트 그룹")
                .build();
        ReflectionTestUtils.setField(group, "id", 3L);

        GroupMember groupMember = GroupMember.createMember(user, group);
        ReflectionTestUtils.setField(groupMember, "id", 1L);
        return groupMember;
    }
}
