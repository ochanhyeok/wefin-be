package com.solv.wefin.domain.group.service;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.group.dto.GroupInviteInfo;
import com.solv.wefin.domain.group.dto.GroupMemberInfo;
import com.solv.wefin.domain.group.dto.LeaveGroupInfo;
import com.solv.wefin.domain.group.dto.MyActiveGroupInfo;
import com.solv.wefin.domain.group.entity.Group;
import com.solv.wefin.domain.group.entity.GroupInvite;
import com.solv.wefin.domain.group.entity.GroupMember;
import com.solv.wefin.domain.group.repository.GroupInviteRepository;
import com.solv.wefin.domain.group.repository.GroupMemberRepository;
import com.solv.wefin.domain.group.repository.GroupRepository;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupService {

    private static final long MAX_GROUP_MEMBER_COUNT = 6L;

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupInviteRepository groupInviteRepository;
    private final UserRepository userRepository;

    @Transactional
    public Group createDefaultGroup(User user) {
        Group group = Group.createHomeGroup(user.getNickname() + "의 그룹");
        Group savedGroup = groupRepository.save(group);

        user.setHomeGroup(savedGroup);

        GroupMember groupMember = GroupMember.createLeader(user, savedGroup);
        groupMemberRepository.save(groupMember);

        return savedGroup;
    }

    @Transactional
    public GroupMemberInfo createSharedGroup(UUID userId, String groupName) {
        User user = getUserForMembershipTransition(userId);

        GroupMember currentActiveMember = groupMemberRepository
                .findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE)
                .orElse(null);

        if (currentActiveMember != null && currentActiveMember.getGroup().isSharedGroup()) {
            throw new BusinessException(ErrorCode.GROUP_CREATE_REQUIRES_HOME);
        }

        if (currentActiveMember != null) {
            currentActiveMember.deactivate();
            // deactivate()가 DB에 반영되기 전에 새 ACTIVE가 들어가면서 유니크 제약 충돌이 날 수 있어 flush로 순서 보장
            groupMemberRepository.flush();
        }

        Group group = Group.createSharedGroup(groupName);
        Group savedGroup = groupRepository.save(group);

        GroupMember leader = GroupMember.createLeader(user, savedGroup);
        groupMemberRepository.save(leader);

        return GroupMemberInfo.from(leader);
    }

    public List<GroupMemberInfo> getActiveMembers(Long groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));

        return groupMemberRepository.findByGroupAndStatusWithUser(
                        group,
                        GroupMember.GroupMemberStatus.ACTIVE
                ).stream()
                .map(GroupMemberInfo::from)
                .toList();
    }

    public MyActiveGroupInfo getMyActiveGroup(UUID userId) {
        GroupMember activeMember = groupMemberRepository
                .findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_MEMBER_NOT_FOUND));

        Group group = activeMember.getGroup();

        return new MyActiveGroupInfo(
                group.getId(),
                group.getName(),
                group.isHomeGroup()
        );
    }

    @Transactional
    public GroupInviteInfo createInviteCode(Long groupId, UUID userId) {
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));

        if (group.isHomeGroup()) {
            throw new BusinessException(ErrorCode.GROUP_HOME_INVITE_NOT_ALLOWED);
        }

        boolean isMember = groupMemberRepository.existsByUser_UserIdAndGroupAndStatus(
                userId,
                group,
                GroupMember.GroupMemberStatus.ACTIVE
        );

        if (!isMember) {
            throw new BusinessException(ErrorCode.GROUP_INVITE_FORBIDDEN);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        OffsetDateTime now = OffsetDateTime.now();

        GroupInvite invite = groupInviteRepository
                .findFirstByGroupAndStatusAndExpiredAtAfterOrderByCreatedAtDesc(
                        group,
                        GroupInvite.InviteStatus.PENDING,
                        now
                )
                .orElseGet(() -> groupInviteRepository.save(GroupInvite.create(group, user)));

        return GroupInviteInfo.from(invite);
    }

    @Transactional(readOnly = true)
    public GroupInviteInfo getLatestInviteCode(Long groupId, UUID userId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));

        if (group.isHomeGroup()) {
            throw new BusinessException(ErrorCode.GROUP_HOME_INVITE_NOT_ALLOWED);
        }

        boolean isMember = groupMemberRepository.existsByUser_UserIdAndGroupAndStatus(
                userId,
                group,
                GroupMember.GroupMemberStatus.ACTIVE
        );

        if (!isMember) {
            throw new BusinessException(ErrorCode.GROUP_INVITE_FORBIDDEN);
        }

        OffsetDateTime now = OffsetDateTime.now();

        GroupInvite invite = groupInviteRepository
                .findFirstByGroupAndStatusAndExpiredAtAfterOrderByCreatedAtDesc(
                        group,
                        GroupInvite.InviteStatus.PENDING,
                        now
                )
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_INVITE_NOT_FOUND));

        return GroupInviteInfo.from(invite);
    }

    @Transactional
    public GroupMemberInfo joinGroup(UUID userId, UUID inviteCode) {
        GroupInvite invite = groupInviteRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_INVITE_NOT_FOUND));

        OffsetDateTime now = OffsetDateTime.now();

        if (invite.isExpired(now)) {
            throw new BusinessException(ErrorCode.GROUP_INVITE_EXPIRED);
        }

        User user = getUserForMembershipTransition(userId);

        Group targetGroup = groupRepository.findByIdForUpdate(invite.getGroup().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));

        if (targetGroup.isHomeGroup()) {
            throw new BusinessException(ErrorCode.GROUP_HOME_JOIN_NOT_ALLOWED);
        }

        GroupMember currentActiveMember = groupMemberRepository
                .findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE)
                .orElse(null);

        if (currentActiveMember != null
                && currentActiveMember.getGroup().getId().equals(targetGroup.getId())) {
            throw new BusinessException(ErrorCode.GROUP_ALREADY_JOINED);
        }

        long activeMemberCount = groupMemberRepository.countByGroupAndStatus(
                targetGroup,
                GroupMember.GroupMemberStatus.ACTIVE
        );

        if (activeMemberCount >= MAX_GROUP_MEMBER_COUNT) {
            throw new BusinessException(ErrorCode.GROUP_FULL);
        }

        if (currentActiveMember != null) {
            currentActiveMember.deactivate();
            groupMemberRepository.flush();
        }

        GroupMember targetMembership = groupMemberRepository
                .findByUser_UserIdAndGroup_Id(userId, targetGroup.getId())
                .orElse(null);

        if (targetMembership != null) {
            targetMembership.activate();
            return GroupMemberInfo.from(targetMembership);
        }

        GroupMember newMember = GroupMember.createMember(user, targetGroup);
        groupMemberRepository.save(newMember);

        return GroupMemberInfo.from(newMember);
    }

    @Transactional
    public GroupMemberInfo assignUserToSharedGroup(UUID userId, Long groupId) {
        User user = getUserForMembershipTransition(userId);

        Group targetGroup = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));

        if (targetGroup.isHomeGroup()) {
            throw new BusinessException(ErrorCode.GROUP_HOME_JOIN_NOT_ALLOWED);
        }

        GroupMember currentActiveMember = groupMemberRepository
                .findByUser_UserIdAndStatus(userId, GroupMember.GroupMemberStatus.ACTIVE)
                .orElse(null);

        if (currentActiveMember != null
                && currentActiveMember.getGroup().getId().equals(targetGroup.getId())) {
            throw new BusinessException(ErrorCode.GROUP_ALREADY_JOINED);
        }

        long activeMemberCount = groupMemberRepository.countByGroupAndStatus(
                targetGroup,
                GroupMember.GroupMemberStatus.ACTIVE
        );

        if (activeMemberCount >= MAX_GROUP_MEMBER_COUNT) {
            throw new BusinessException(ErrorCode.GROUP_FULL);
        }

        if (currentActiveMember != null) {
            currentActiveMember.deactivate();
            groupMemberRepository.flush();
        }

        GroupMember targetMembership = groupMemberRepository
                .findByUser_UserIdAndGroup_Id(userId, targetGroup.getId())
                .orElse(null);

        if (targetMembership != null) {
            targetMembership.activate();
            return GroupMemberInfo.from(targetMembership);
        }

        GroupMember newMember = GroupMember.createMember(user, targetGroup);
        groupMemberRepository.save(newMember);

        return GroupMemberInfo.from(newMember);
    }

    @Transactional
    public LeaveGroupInfo leaveGroup(Long groupId, UUID userId) {
        User user = getUserForMembershipTransition(userId);

        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));

        if (group.isHomeGroup()) {
            throw new BusinessException(ErrorCode.GROUP_HOME_LEAVE_NOT_ALLOWED);
        }

        GroupMember leavingMember = groupMemberRepository.findByUser_UserIdAndGroup_Id(userId, groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_MEMBER_NOT_FOUND));

        if (!leavingMember.isActive()) {
            throw new BusinessException(ErrorCode.GROUP_MEMBER_ALREADY_INACTIVE);
        }

        boolean wasLeader = leavingMember.isLeader();

        if (wasLeader) {
            leavingMember.changeRoleToMember();
        }

        leavingMember.deactivate();
        groupMemberRepository.flush();

        long remainingActiveMemberCount = groupMemberRepository.countByGroupAndStatus(
                group,
                GroupMember.GroupMemberStatus.ACTIVE
        );

        if (wasLeader && remainingActiveMemberCount > 0) {
            GroupMember nextLeader = groupMemberRepository
                    .findFirstByGroupAndStatusAndUser_UserIdNotOrderByIdAsc(
                            group,
                            GroupMember.GroupMemberStatus.ACTIVE,
                            userId
                    )
                    .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_LEADER_TRANSFER_FAILED));

            nextLeader.changeRoleToLeader();
        }

        GroupMember homeGroupMember = restoreOrCreateHomeMembership(user);
        homeGroupMember.activate();

        return new LeaveGroupInfo(
                group.getId(),
                homeGroupMember.getGroup().getId()
        );
    }

    private User getUserForMembershipTransition(UUID userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private GroupMember restoreOrCreateHomeMembership(User user) {
        Group homeGroup = user.getHomeGroup();

        if (homeGroup != null) {
            Group managedHomeGroup = groupRepository.findById(homeGroup.getId())
                    .orElse(null);

            if (managedHomeGroup != null) {
                return groupMemberRepository.findByUser_UserIdAndGroup_Id(user.getUserId(), managedHomeGroup.getId())
                        .orElseGet(() -> groupMemberRepository.save(GroupMember.createLeader(user, managedHomeGroup)));
            }
        }

        return createDefaultGroupAndGetMembership(user);
    }

    private GroupMember createDefaultGroupAndGetMembership(User user) {
        Group group = Group.createHomeGroup(user.getNickname() + "의 그룹");
        Group savedGroup = groupRepository.save(group);

        user.setHomeGroup(savedGroup);

        GroupMember groupMember = GroupMember.createLeader(user, savedGroup);
        return groupMemberRepository.save(groupMember);
    }
}
