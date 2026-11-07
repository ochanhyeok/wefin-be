package com.solv.wefin.domain.auth.service;

import com.solv.wefin.domain.auth.dto.IssueAccountCommand;
import com.solv.wefin.domain.auth.dto.IssuedAccountInfo;
import com.solv.wefin.domain.auth.dto.LoginInfo;
import com.solv.wefin.domain.auth.dto.SignupCommand;
import com.solv.wefin.domain.auth.dto.SignupInfo;
import com.solv.wefin.domain.auth.entity.RefreshToken;
import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.entity.UserAccountType;
import com.solv.wefin.domain.auth.entity.UserStatus;
import com.solv.wefin.domain.auth.entity.VerificationPurpose;
import com.solv.wefin.domain.auth.repository.RefreshTokenRepository;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.group.dto.GroupMemberInfo;
import com.solv.wefin.domain.group.entity.Group;
import com.solv.wefin.domain.group.entity.GroupMember;
import com.solv.wefin.domain.group.repository.GroupMemberRepository;
import com.solv.wefin.domain.group.service.GroupService;
import com.solv.wefin.domain.quest.entity.QuestEventType;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.domain.quest.service.UserQuestService;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.global.config.security.JwtProvider;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AuthService {

    private static final String UK_USERS_EMAIL = "uk_users_email";
    private static final String UK_USERS_NICKNAME = "uk_users_nickname";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final GroupService groupService;
    private final JwtProvider jwtProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationService emailVerificationService;
    private final QuestProgressService questProgressService;
    private final VirtualAccountService virtualAccountService;
    private final UserQuestService userQuestService;
    private final GroupMemberRepository groupMemberRepository;

    @Transactional
    public SignupInfo signup(SignupCommand command) {
        NormalizedAccountInput input = normalizeAccountInput(
                command.email(),
                command.nickname(),
                command.password()
        );

        emailVerificationService.validateVerifiedEmail(input.email(), VerificationPurpose.SIGNUP);

        CreatedAccount createdAccount = createUserWithDefaults(
                input,
                UserAccountType.NORMAL
        );
        User savedUser = createdAccount.user();

        emailVerificationService.consumeVerifiedEmail(savedUser.getEmail(), VerificationPurpose.SIGNUP);

        return new SignupInfo(
                savedUser.getUserId(),
                savedUser.getEmail(),
                savedUser.getNickname()
        );
    }

    @Transactional
    public IssuedAccountInfo issueAccount(IssueAccountCommand command) {
        if (command.accountType() == null || command.accountType() == UserAccountType.NORMAL) {
            throw new BusinessException(ErrorCode.AUTH_VALIDATION_FAILED);
        }

        NormalizedAccountInput input = normalizeAccountInput(
                command.email(),
                command.nickname(),
                command.password()
        );

        CreatedAccount createdAccount = createUserWithDefaults(
                input,
                command.accountType()
        );
        Group activeGroup = createdAccount.activeGroup();

        if (command.targetGroupId() != null) {
            GroupMemberInfo activeGroupInfo = groupService.assignUserToSharedGroup(
                    createdAccount.user().getUserId(),
                    command.targetGroupId()
            );

            return new IssuedAccountInfo(
                    createdAccount.user().getUserId(),
                    createdAccount.user().getEmail(),
                    createdAccount.user().getNickname(),
                    createdAccount.user().getAccountType(),
                    activeGroupInfo.groupId(),
                    activeGroupInfo.groupName()
            );
        }

        return new IssuedAccountInfo(
                createdAccount.user().getUserId(),
                createdAccount.user().getEmail(),
                createdAccount.user().getNickname(),
                createdAccount.user().getAccountType(),
                activeGroup.getId(),
                activeGroup.getName()
        );
    }

    private CreatedAccount createUserWithDefaults(
            NormalizedAccountInput input,
            UserAccountType accountType
    ) {
        if (userRepository.existsByEmail(input.email())) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED);
        }
        if (userRepository.existsByNickname(input.nickname())) {
            throw new BusinessException(ErrorCode.AUTH_NICKNAME_DUPLICATED);
        }

        try {
            String encodedPassword = passwordEncoder.encode(input.password());
            User user = accountType == UserAccountType.NORMAL
                    ? User.createNormalAccount(
                            input.email(),
                            input.nickname(),
                            encodedPassword
                    )
                    : User.createIssuedAccount(
                            input.email(),
                            input.nickname(),
                            encodedPassword,
                            accountType
                    );

            User savedUser = userRepository.saveAndFlush(user);
            Group homeGroup = groupService.createDefaultGroup(savedUser);
            savedUser.setHomeGroup(homeGroup);
            virtualAccountService.createAccount(savedUser.getUserId());

            return new CreatedAccount(savedUser, homeGroup);

        } catch (DataIntegrityViolationException e) {
            throw mapConstraintViolation(e);
        }
    }

    private NormalizedAccountInput normalizeAccountInput(String email, String nickname, String password) {
        if (email == null || nickname == null || password == null) {
            throw new BusinessException(ErrorCode.AUTH_VALIDATION_FAILED);
        }

        email = email.trim().toLowerCase(Locale.ROOT);
        nickname = nickname.trim();

        if (email.isBlank() || nickname.isBlank() || password.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_VALIDATION_FAILED);
        }

        if (!password.equals(password.trim())) {
            throw new BusinessException(ErrorCode.AUTH_VALIDATION_FAILED);
        }

        return new NormalizedAccountInput(email, nickname, password);
    }

    @Transactional
    public void resetPassword(String email, String newPassword) {
        if (email == null || newPassword == null) {
            throw new BusinessException(ErrorCode.AUTH_VALIDATION_FAILED);
        }

        email = email.trim().toLowerCase(Locale.ROOT);

        if (email.isBlank() || newPassword.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_VALIDATION_FAILED);
        }

        if (!newPassword.equals(newPassword.trim())) {
            throw new BusinessException(ErrorCode.AUTH_VALIDATION_FAILED);
        }

        emailVerificationService.validateVerifiedEmail(email, VerificationPurpose.PASSWORD_RESET);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        user.changePassword(passwordEncoder.encode(newPassword));
        emailVerificationService.consumeVerifiedEmail(user.getEmail(), VerificationPurpose.PASSWORD_RESET);
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {

        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_MISMATCH);
        }

        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_SAME_AS_OLD);
        }

        user.changePassword(passwordEncoder.encode(newPassword));
    }

    @Transactional
    public LoginInfo login(String email, String password) {
        if (email == null || password == null) {
            throw new BusinessException(ErrorCode.AUTH_VALIDATION_FAILED);
        }

        email = email.trim().toLowerCase(Locale.ROOT);

        if (email.isBlank() || password.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_VALIDATION_FAILED);
        }

        if (!password.equals(password.trim())) {
            throw new BusinessException(ErrorCode.AUTH_VALIDATION_FAILED);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_LOGIN_FAILED));

        if (user.getStatus() == UserStatus.LOCKED) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
        }

        String accessToken = jwtProvider.generateAccessToken(user.getUserId());
        String refreshTokenValue = jwtProvider.generateRefreshToken(user.getUserId());
        OffsetDateTime refreshTokenExpiresAt = jwtProvider.getExpiration(refreshTokenValue);

        RefreshToken refreshToken = refreshTokenRepository.findById(user.getUserId())
                .map(saved -> {
                    saved.update(refreshTokenValue, refreshTokenExpiresAt);
                    return saved;
                })
                .orElseGet(() -> RefreshToken.builder()
                        .userId(user.getUserId())
                        .token(refreshTokenValue)
                        .expiresAt(refreshTokenExpiresAt)
                        .build());

        refreshTokenRepository.save(refreshToken);

        userQuestService.getOrIssueTodayUserQuests(user.getUserId());

        try {
            questProgressService.handleEvent(user.getUserId(), QuestEventType.LOGIN);
        } catch (RuntimeException e) {
            log.warn("로그인 퀘스트 반영 실패 userId={}", user.getUserId(), e);
        }

        return new LoginInfo(
                user.getUserId(),
                user.getNickname(),
                accessToken,
                refreshTokenValue
        );
    }

    @Transactional
    public String refresh(String refreshToken) {
        RefreshToken savedToken = getValidRefreshTokenForUpdate(refreshToken);

        return jwtProvider.generateAccessToken(savedToken.getUserId());
    }

    @Transactional
    public void logout(UUID userId, String refreshToken) {
        RefreshToken savedToken = getValidRefreshTokenForUpdate(refreshToken);

        if (!savedToken.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_TOKEN);
        }
        savedToken.revoke();
    }

    @Transactional
    public void withdraw(UUID userId, String password) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_MISMATCH);
        }

        List<GroupMember> activeMembers = groupMemberRepository.findAllByUser_UserIdAndStatus(
                userId,
                GroupMember.GroupMemberStatus.ACTIVE
        );

        for (GroupMember activeMember : activeMembers) {
            boolean wasLeader = activeMember.isLeader();
            Group group = activeMember.getGroup();

            if (wasLeader) {
                activeMember.changeRoleToMember();
            }

            activeMember.deactivate();
            groupMemberRepository.flush();

            if (wasLeader && group.isSharedGroup()) {
                long remainingActiveMemberCount = groupMemberRepository.countByGroupAndStatus(
                        group,
                        GroupMember.GroupMemberStatus.ACTIVE
                );

                if (remainingActiveMemberCount > 0) {
                    GroupMember nextLeader = groupMemberRepository
                            .findFirstByGroupAndStatusAndUser_UserIdNotOrderByIdAsc(
                                    group,
                                    GroupMember.GroupMemberStatus.ACTIVE,
                                    userId
                            )
                            .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_LEADER_TRANSFER_FAILED));

                    nextLeader.changeRoleToLeader();
                }
            }
        }

        refreshTokenRepository.findById(userId)
                .ifPresent(RefreshToken::revoke);

        user.withdraw();
    }

    private RefreshToken getValidRefreshTokenForUpdate(String refreshToken) {
        if (!jwtProvider.isValid(refreshToken) || !"refresh".equals(jwtProvider.getTokenType(refreshToken))) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_TOKEN);
        }

        UUID userId = jwtProvider.getUserId(refreshToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_TOKEN));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_TOKEN);
        }

        RefreshToken savedToken = refreshTokenRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_TOKEN));

        if (!savedToken.getToken().equals(refreshToken) || savedToken.isRevoked()) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_TOKEN);
        }

        if (!savedToken.getExpiresAt().isAfter(OffsetDateTime.now())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_TOKEN);
        }

        return savedToken;
    }

    private BusinessException mapConstraintViolation(DataIntegrityViolationException e) {
        Throwable cause = e;

        while (cause != null) {
            if (cause instanceof ConstraintViolationException cve) {
                String constraint = cve.getConstraintName();

                if (UK_USERS_EMAIL.equals(constraint)) {
                    return new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED);
                }
                if (UK_USERS_NICKNAME.equals(constraint)) {
                    return new BusinessException(ErrorCode.AUTH_NICKNAME_DUPLICATED);
                }
            }
            cause = cause.getCause();
        }

        return new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }

    private record NormalizedAccountInput(
            String email,
            String nickname,
            String password
    ) {
    }

    private record CreatedAccount(
            User user,
            Group activeGroup
    ) {
    }
}
