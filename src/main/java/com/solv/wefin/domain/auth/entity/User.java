package com.solv.wefin.domain.auth.entity;

import com.solv.wefin.domain.group.entity.Group;
import com.solv.wefin.global.common.BaseEntity;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "nickname", nullable = false, length = 20)
    private String nickname;

    @Column(name = "password", nullable = false, length = 255)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private UserAccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_group_id")
    private Group homeGroup;

    @Column(name = "last_read_global_message_id")
    private Long lastReadGlobalMessageId;

    @Column(name = "last_read_global_at")
    private OffsetDateTime lastReadGlobalAt;

    private User(String email, String nickname, String password, UserAccountType accountType, UserRole role) {
        this.email = email;
        this.nickname = nickname;
        this.password = password;
        this.status = UserStatus.ACTIVE;
        this.accountType = accountType;
        this.role = role;
    }

    public static User createNormalAccount(String email, String nickname, String password) {
        return new User(email, nickname, password, UserAccountType.NORMAL, UserRole.USER);
    }

    public static User createIssuedAccount(
            String email,
            String nickname,
            String password,
            UserAccountType accountType
    ) {
        if (accountType == UserAccountType.NORMAL) {
            throw new BusinessException(ErrorCode.AUTH_VALIDATION_FAILED);
        }

        return new User(email, nickname, password, accountType, UserRole.USER);
    }

    public void setHomeGroup(Group homeGroup) {
        this.homeGroup = homeGroup;
    }

    public void changePassword(String password) {
        this.password = password;
    }

    public void markGlobalChatRead(Long messageId) {
        if (messageId == null) {
            this.lastReadGlobalAt = OffsetDateTime.now();
            return;
        }

        if (this.lastReadGlobalMessageId == null || messageId >= this.lastReadGlobalMessageId) {
            this.lastReadGlobalMessageId = messageId;
            this.lastReadGlobalAt = OffsetDateTime.now();
        }
    }

    public void withdraw() {
        this.status = UserStatus.WITHDRAWN;
        this.homeGroup = null;
        this.email = "withdrawn_" + this.userId + "@deleted.local";
        this.nickname = "wd_" + this.userId.toString().substring(0, 8);
    }

    public boolean isAdmin() {
        return this.role == UserRole.ADMIN;
    }
}
