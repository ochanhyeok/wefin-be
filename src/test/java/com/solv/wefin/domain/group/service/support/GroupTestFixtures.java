package com.solv.wefin.domain.group.service.support;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.group.entity.Group;
import com.solv.wefin.domain.group.entity.GroupInvite;
import com.solv.wefin.domain.group.entity.GroupMember;
import com.solv.wefin.domain.group.entity.GroupType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class GroupTestFixtures {

    private GroupTestFixtures() {
    }

    public static Group createGroup(Long id, String name, GroupType groupType) throws Exception {
        Constructor<Group> constructor = Group.class.getDeclaredConstructor(String.class, GroupType.class);
        constructor.setAccessible(true);
        Group group = constructor.newInstance(name, groupType);

        Field idField = Group.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(group, id);

        return group;
    }

    public static User createUser(UUID userId, String email, String nickname, String password) throws Exception {
        User user = User.createNormalAccount(email, nickname, password);

        Field userIdField = User.class.getDeclaredField("userId");
        userIdField.setAccessible(true);
        userIdField.set(user, userId);

        return user;
    }

    public static GroupMember createGroupMember(
            Long id,
            User user,
            Group group,
            GroupMember.GroupMemberRole role,
            GroupMember.GroupMemberStatus status
    ) throws Exception {
        Constructor<GroupMember> constructor = GroupMember.class.getDeclaredConstructor(
                User.class,
                Group.class,
                GroupMember.GroupMemberRole.class,
                GroupMember.GroupMemberStatus.class
        );
        constructor.setAccessible(true);
        GroupMember groupMember = constructor.newInstance(user, group, role, status);

        Field idField = GroupMember.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(groupMember, id);

        return groupMember;
    }

    public static GroupInvite createGroupInvite(
            Long id,
            Group group,
            User createdBy,
            UUID inviteCode,
            GroupInvite.InviteStatus status
    ) throws Exception {
        return createGroupInvite(
                id,
                group,
                createdBy,
                inviteCode,
                status,
                OffsetDateTime.now().plusHours(24)
        );
    }

    public static GroupInvite createGroupInvite(
            Long id,
            Group group,
            User createdBy,
            UUID inviteCode,
            GroupInvite.InviteStatus status,
            OffsetDateTime expiredAt
    ) throws Exception {
        Constructor<GroupInvite> constructor = GroupInvite.class.getDeclaredConstructor(
                Group.class,
                User.class,
                UUID.class,
                GroupInvite.InviteStatus.class,
                OffsetDateTime.class
        );
        constructor.setAccessible(true);

        GroupInvite groupInvite = constructor.newInstance(
                group,
                createdBy,
                inviteCode,
                status,
                expiredAt
        );

        Field idField = GroupInvite.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(groupInvite, id);

        return groupInvite;
    }
}
