package com.solv.wefin.domain.group.service;

import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.group.dto.GroupMemberInfo;
import com.solv.wefin.domain.group.entity.Group;
import com.solv.wefin.domain.group.entity.GroupInvite;
import com.solv.wefin.domain.group.entity.GroupMember;
import com.solv.wefin.domain.group.entity.GroupType;
import com.solv.wefin.domain.group.repository.GroupInviteRepository;
import com.solv.wefin.domain.group.repository.GroupMemberRepository;
import com.solv.wefin.domain.group.repository.GroupRepository;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.solv.wefin.domain.group.service.support.GroupTestFixtures.createGroup;
import static com.solv.wefin.domain.group.service.support.GroupTestFixtures.createGroupInvite;
import static com.solv.wefin.domain.group.service.support.GroupTestFixtures.createGroupMember;
import static com.solv.wefin.domain.group.service.support.GroupTestFixtures.createUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupJoinServiceTest {

    @Mock
    private GroupRepository groupRepository;

    @Mock
    private GroupMemberRepository groupMemberRepository;

    @Mock
    private GroupInviteRepository groupInviteRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private GroupService groupService;

    @Nested
    @DisplayName("joinGroup")
    class JoinGroupTest {

        @Test
        @DisplayName("초대 코드로 SHARED 그룹에 정상 참여한다")
        void joinGroup_success() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID inviteCode = UUID.randomUUID();

            Group targetGroup = createGroup(1L, "공유 그룹", GroupType.SHARED);
            Group homeGroup = createGroup(100L, "홈 그룹", GroupType.HOME);

            var user = createUser(userId, "test@test.com", "유저", "pw");

            GroupInvite invite = createGroupInvite(
                    10L,
                    targetGroup,
                    user,
                    inviteCode,
                    GroupInvite.InviteStatus.PENDING
            );

            GroupMember currentActiveHomeMember = createGroupMember(
                    20L,
                    user,
                    homeGroup,
                    GroupMember.GroupMemberRole.LEADER,
                    GroupMember.GroupMemberStatus.ACTIVE
            );

            GroupMember newMember = createGroupMember(
                    21L,
                    user,
                    targetGroup,
                    GroupMember.GroupMemberRole.MEMBER,
                    GroupMember.GroupMemberStatus.ACTIVE
            );

            when(groupInviteRepository.findByInviteCode(inviteCode))
                    .thenReturn(Optional.of(invite));
            when(groupRepository.findByIdForUpdate(targetGroup.getId()))
                    .thenReturn(Optional.of(targetGroup));
            when(userRepository.findByIdForUpdate(userId))
                    .thenReturn(Optional.of(user));
            when(groupMemberRepository.findByUser_UserIdAndStatus(
                    userId,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(Optional.of(currentActiveHomeMember));
            when(groupMemberRepository.countByGroupAndStatus(
                    targetGroup,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(1L);
            when(groupMemberRepository.findByUser_UserIdAndGroup_Id(userId, targetGroup.getId()))
                    .thenReturn(Optional.empty());
            when(groupMemberRepository.save(any(GroupMember.class)))
                    .thenReturn(newMember);

            GroupMemberInfo result = groupService.joinGroup(userId, inviteCode);

            assertAll(
                    () -> assertThat(result.groupId()).isEqualTo(1L),
                    () -> assertThat(result.groupName()).isEqualTo("공유 그룹"),
                    () -> assertThat(result.role()).isEqualTo("MEMBER"),
                    () -> assertThat(currentActiveHomeMember.isActive()).isFalse(),
                    () -> assertThat(invite.getStatus()).isEqualTo(GroupInvite.InviteStatus.PENDING)
            );

            verify(groupMemberRepository).flush();
            verify(groupMemberRepository).save(any(GroupMember.class));
        }

        @Test
        @DisplayName("기존 멤버십이 있으면 재활성화한다")
        void joinGroup_success_when_membership_exists() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID inviteCode = UUID.randomUUID();

            Group targetGroup = createGroup(1L, "공유 그룹", GroupType.SHARED);
            Group homeGroup = createGroup(100L, "홈 그룹", GroupType.HOME);

            var user = createUser(userId, "test@test.com", "유저", "pw");

            GroupInvite invite = createGroupInvite(
                    10L,
                    targetGroup,
                    user,
                    inviteCode,
                    GroupInvite.InviteStatus.PENDING
            );

            GroupMember currentActiveHomeMember = createGroupMember(
                    20L,
                    user,
                    homeGroup,
                    GroupMember.GroupMemberRole.LEADER,
                    GroupMember.GroupMemberStatus.ACTIVE
            );

            GroupMember existingMembership = createGroupMember(
                    21L,
                    user,
                    targetGroup,
                    GroupMember.GroupMemberRole.MEMBER,
                    GroupMember.GroupMemberStatus.INACTIVE
            );

            when(groupInviteRepository.findByInviteCode(inviteCode))
                    .thenReturn(Optional.of(invite));
            when(groupRepository.findByIdForUpdate(targetGroup.getId()))
                    .thenReturn(Optional.of(targetGroup));
            when(userRepository.findByIdForUpdate(userId))
                    .thenReturn(Optional.of(user));
            when(groupMemberRepository.findByUser_UserIdAndStatus(
                    userId,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(Optional.of(currentActiveHomeMember));
            when(groupMemberRepository.countByGroupAndStatus(
                    targetGroup,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(1L);
            when(groupMemberRepository.findByUser_UserIdAndGroup_Id(userId, targetGroup.getId()))
                    .thenReturn(Optional.of(existingMembership));

            GroupMemberInfo result = groupService.joinGroup(userId, inviteCode);

            assertAll(
                    () -> assertThat(result.groupId()).isEqualTo(1L),
                    () -> assertThat(result.groupName()).isEqualTo("공유 그룹"),
                    () -> assertThat(result.role()).isEqualTo("MEMBER"),
                    () -> assertThat(currentActiveHomeMember.isActive()).isFalse(),
                    () -> assertThat(existingMembership.isActive()).isTrue(),
                    () -> assertThat(invite.getStatus()).isEqualTo(GroupInvite.InviteStatus.PENDING)
            );

            verify(groupMemberRepository).flush();
            verify(groupMemberRepository, never()).save(any(GroupMember.class));
        }

        @Test
        @DisplayName("홈 그룹에는 참여할 수 없다")
        void joinGroup_fail_when_home_group() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID inviteCode = UUID.randomUUID();

            Group homeGroup = createGroup(1L, "홈 그룹", GroupType.HOME);
            var user = createUser(userId, "test@test.com", "유저", "pw");

            GroupInvite invite = createGroupInvite(
                    10L,
                    homeGroup,
                    user,
                    inviteCode,
                    GroupInvite.InviteStatus.PENDING
            );

            when(groupInviteRepository.findByInviteCode(inviteCode))
                    .thenReturn(Optional.of(invite));
            when(userRepository.findByIdForUpdate(userId))
                    .thenReturn(Optional.of(user));
            when(groupRepository.findByIdForUpdate(homeGroup.getId()))
                    .thenReturn(Optional.of(homeGroup));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> groupService.joinGroup(userId, inviteCode)
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.GROUP_HOME_JOIN_NOT_ALLOWED);

            verify(groupMemberRepository, never()).findByUser_UserIdAndStatus(any(), any());
            verify(groupMemberRepository, never()).countByGroupAndStatus(any(), any());
            verify(groupMemberRepository, never()).findByUser_UserIdAndGroup_Id(any(), anyLong());
            verify(groupMemberRepository, never()).flush();
            verify(groupMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("이미 같은 그룹에 ACTIVE 상태로 참여 중이면 예외가 발생한다")
        void joinGroup_fail_when_already_joined() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID inviteCode = UUID.randomUUID();

            Group targetGroup = createGroup(1L, "공유 그룹", GroupType.SHARED);
            var user = createUser(userId, "test@test.com", "유저", "pw");

            GroupInvite invite = createGroupInvite(
                    10L,
                    targetGroup,
                    user,
                    inviteCode,
                    GroupInvite.InviteStatus.PENDING
            );

            GroupMember currentActiveMember = createGroupMember(
                    20L,
                    user,
                    targetGroup,
                    GroupMember.GroupMemberRole.MEMBER,
                    GroupMember.GroupMemberStatus.ACTIVE
            );

            when(groupInviteRepository.findByInviteCode(inviteCode))
                    .thenReturn(Optional.of(invite));
            when(groupRepository.findByIdForUpdate(targetGroup.getId()))
                    .thenReturn(Optional.of(targetGroup));
            when(userRepository.findByIdForUpdate(userId))
                    .thenReturn(Optional.of(user));
            when(groupMemberRepository.findByUser_UserIdAndStatus(
                    userId,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(Optional.of(currentActiveMember));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> groupService.joinGroup(userId, inviteCode)
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.GROUP_ALREADY_JOINED);

            verify(groupMemberRepository, never()).countByGroupAndStatus(any(), any());
            verify(groupMemberRepository, never()).findByUser_UserIdAndGroup_Id(any(), anyLong());
            verify(groupMemberRepository, never()).flush();
            verify(groupMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("정원이 가득 찬 그룹은 참여할 수 없다")
        void joinGroup_fail_when_group_full() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID inviteCode = UUID.randomUUID();

            Group targetGroup = createGroup(1L, "공유 그룹", GroupType.SHARED);
            Group homeGroup = createGroup(100L, "홈 그룹", GroupType.HOME);

            var user = createUser(userId, "test@test.com", "유저", "pw");

            GroupInvite invite = createGroupInvite(
                    10L,
                    targetGroup,
                    user,
                    inviteCode,
                    GroupInvite.InviteStatus.PENDING
            );

            GroupMember currentActiveHomeMember = createGroupMember(
                    20L,
                    user,
                    homeGroup,
                    GroupMember.GroupMemberRole.LEADER,
                    GroupMember.GroupMemberStatus.ACTIVE
            );

            when(groupInviteRepository.findByInviteCode(inviteCode))
                    .thenReturn(Optional.of(invite));
            when(groupRepository.findByIdForUpdate(targetGroup.getId()))
                    .thenReturn(Optional.of(targetGroup));
            when(userRepository.findByIdForUpdate(userId))
                    .thenReturn(Optional.of(user));
            when(groupMemberRepository.findByUser_UserIdAndStatus(
                    userId,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(Optional.of(currentActiveHomeMember));
            when(groupMemberRepository.countByGroupAndStatus(
                    targetGroup,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(6L);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> groupService.joinGroup(userId, inviteCode)
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.GROUP_FULL);

            verify(groupMemberRepository, never()).findByUser_UserIdAndGroup_Id(any(), anyLong());
            verify(groupMemberRepository, never()).flush();
            verify(groupMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("expiredAt이 지난 초대 코드는 사용할 수 없다")
        void joinGroup_fail_when_invite_expired() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID inviteCode = UUID.randomUUID();

            Group targetGroup = createGroup(1L, "공유 그룹", GroupType.SHARED);
            var user = createUser(userId, "test@test.com", "유저", "pw");

            GroupInvite invite = createGroupInvite(
                    10L,
                    targetGroup,
                    user,
                    inviteCode,
                    GroupInvite.InviteStatus.PENDING,
                    OffsetDateTime.now().minusHours(1)
            );

            when(groupInviteRepository.findByInviteCode(inviteCode))
                    .thenReturn(Optional.of(invite));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> groupService.joinGroup(userId, inviteCode)
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.GROUP_INVITE_EXPIRED);
            verify(groupMemberRepository, never()).flush();
        }

        @Test
        @DisplayName("존재하지 않는 초대 코드는 예외가 발생한다")
        void joinGroup_fail_when_invite_not_found() {
            UUID userId = UUID.randomUUID();
            UUID inviteCode = UUID.randomUUID();

            when(groupInviteRepository.findByInviteCode(inviteCode))
                    .thenReturn(Optional.empty());

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> groupService.joinGroup(userId, inviteCode)
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.GROUP_INVITE_NOT_FOUND);
            verify(groupMemberRepository, never()).flush();
        }
    }

    @Nested
    @DisplayName("assignUserToSharedGroup")
    class AssignUserToSharedGroupTest {

        @Test
        @DisplayName("사용자를 지정 SHARED 그룹에 배정한다")
        void assignUserToSharedGroup_success() throws Exception {
            UUID userId = UUID.randomUUID();
            Group targetGroup = createGroup(1L, "대회 A반", GroupType.SHARED);
            Group homeGroup = createGroup(100L, "홈 그룹", GroupType.HOME);
            var user = createUser(userId, "contest@test.com", "대회유저", "pw");
            GroupMember currentActiveHomeMember = createGroupMember(
                    20L,
                    user,
                    homeGroup,
                    GroupMember.GroupMemberRole.LEADER,
                    GroupMember.GroupMemberStatus.ACTIVE
            );
            GroupMember newMember = createGroupMember(
                    21L,
                    user,
                    targetGroup,
                    GroupMember.GroupMemberRole.MEMBER,
                    GroupMember.GroupMemberStatus.ACTIVE
            );

            when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
            when(groupRepository.findByIdForUpdate(targetGroup.getId())).thenReturn(Optional.of(targetGroup));
            when(groupMemberRepository.findByUser_UserIdAndStatus(
                    userId,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(Optional.of(currentActiveHomeMember));
            when(groupMemberRepository.countByGroupAndStatus(
                    targetGroup,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(1L);
            when(groupMemberRepository.findByUser_UserIdAndGroup_Id(userId, targetGroup.getId()))
                    .thenReturn(Optional.empty());
            when(groupMemberRepository.save(any(GroupMember.class))).thenReturn(newMember);

            GroupMemberInfo result = groupService.assignUserToSharedGroup(userId, targetGroup.getId());

            assertAll(
                    () -> assertThat(result.groupId()).isEqualTo(targetGroup.getId()),
                    () -> assertThat(result.groupName()).isEqualTo("대회 A반"),
                    () -> assertThat(result.role()).isEqualTo("MEMBER"),
                    () -> assertThat(currentActiveHomeMember.isActive()).isFalse()
            );

            verify(groupMemberRepository).flush();
            verify(groupMemberRepository).save(any(GroupMember.class));
        }

        @Test
        @DisplayName("HOME 그룹에는 관리자 배정을 할 수 없다")
        void assignUserToSharedGroup_fail_when_home_group() throws Exception {
            UUID userId = UUID.randomUUID();
            Group homeGroup = createGroup(100L, "홈 그룹", GroupType.HOME);
            var user = createUser(userId, "contest@test.com", "대회유저", "pw");

            when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
            when(groupRepository.findByIdForUpdate(homeGroup.getId())).thenReturn(Optional.of(homeGroup));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> groupService.assignUserToSharedGroup(userId, homeGroup.getId())
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.GROUP_HOME_JOIN_NOT_ALLOWED);

            verify(groupMemberRepository, never()).findByUser_UserIdAndStatus(any(), any());
            verify(groupMemberRepository, never()).countByGroupAndStatus(any(), any());
            verify(groupMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("정원이 찬 그룹에는 관리자 배정을 할 수 없다")
        void assignUserToSharedGroup_fail_when_group_full() throws Exception {
            UUID userId = UUID.randomUUID();
            Group targetGroup = createGroup(1L, "대회 A반", GroupType.SHARED);
            Group homeGroup = createGroup(100L, "홈 그룹", GroupType.HOME);
            var user = createUser(userId, "contest@test.com", "대회유저", "pw");
            GroupMember currentActiveHomeMember = createGroupMember(
                    20L,
                    user,
                    homeGroup,
                    GroupMember.GroupMemberRole.LEADER,
                    GroupMember.GroupMemberStatus.ACTIVE
            );

            when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
            when(groupRepository.findByIdForUpdate(targetGroup.getId())).thenReturn(Optional.of(targetGroup));
            when(groupMemberRepository.findByUser_UserIdAndStatus(
                    userId,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(Optional.of(currentActiveHomeMember));
            when(groupMemberRepository.countByGroupAndStatus(
                    targetGroup,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(6L);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> groupService.assignUserToSharedGroup(userId, targetGroup.getId())
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.GROUP_FULL);
            assertThat(currentActiveHomeMember.isActive()).isTrue();

            verify(groupMemberRepository, never()).flush();
            verify(groupMemberRepository, never()).save(any());
        }
    }
}
