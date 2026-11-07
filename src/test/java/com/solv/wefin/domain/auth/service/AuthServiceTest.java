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
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private GroupService groupService;

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private QuestProgressService questProgressService;

    @Mock
    private VirtualAccountService virtualAccountService;

    @Mock
    private UserQuestService userQuestService;

    @Mock
    private GroupMemberRepository groupMemberRepository;

    @InjectMocks
    private AuthService authService;

    @Nested
    @DisplayName("signup")
    class SignupTest {

        @Test
        @DisplayName("회원가입에 성공한다")
        void signup_success() {
            String rawEmail = "  TEST@Example.com  ";
            String rawNickname = "  testuser  ";
            String rawPassword = "pass1234";

            UUID userId = UUID.randomUUID();

            when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
            when(userRepository.existsByNickname("testuser")).thenReturn(false);
            when(passwordEncoder.encode("pass1234")).thenReturn("encoded-password");

            User savedUser = User.createNormalAccount(
                    "test@example.com",
                    "testuser",
                    "encoded-password"
            );

            ReflectionTestUtils.setField(savedUser, "userId", userId);

            when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);

            Group homeGroup = Group.builder()
                    .name("testuser의 그룹")
                    .build();

            when(groupService.createDefaultGroup(savedUser)).thenReturn(homeGroup);

            SignupInfo response = authService.signup(
                    new SignupCommand(rawEmail, rawNickname, rawPassword)
            );

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(emailVerificationService)
                    .validateVerifiedEmail("test@example.com", VerificationPurpose.SIGNUP);
            verify(userRepository).saveAndFlush(captor.capture());
            verify(groupService).createDefaultGroup(savedUser);
            verify(virtualAccountService).createAccount(userId);
            verify(emailVerificationService)
                    .consumeVerifiedEmail("test@example.com", VerificationPurpose.SIGNUP);

            User capturedUser = captor.getValue();

            assertAll(
                    () -> assertThat(capturedUser.getEmail()).isEqualTo("test@example.com"),
                    () -> assertThat(capturedUser.getNickname()).isEqualTo("testuser"),
                    () -> assertThat(capturedUser.getPassword()).isEqualTo("encoded-password"),
                    () -> assertThat(savedUser.getHomeGroup()).isEqualTo(homeGroup),
                    () -> assertThat(response.userId()).isEqualTo(userId),
                    () -> assertThat(response.email()).isEqualTo("test@example.com"),
                    () -> assertThat(response.nickname()).isEqualTo("testuser")
            );
        }

        @Test
        @DisplayName("이메일 인증이 완료되지 않으면 회원가입에 실패한다")
        void signup_fail_when_email_not_verified() {
            doThrow(new BusinessException(ErrorCode.AUTH_EMAIL_NOT_VERIFIED))
                    .when(emailVerificationService)
                    .validateVerifiedEmail("test@example.com", VerificationPurpose.SIGNUP);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.signup(
                            new SignupCommand("test@example.com", "nickname", "pass1234")
                    )
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_EMAIL_NOT_VERIFIED);
            verify(userRepository, never()).existsByEmail(anyString());
            verify(userRepository, never()).saveAndFlush(any(User.class));
            verify(groupService, never()).createDefaultGroup(any(User.class));
            verify(virtualAccountService, never()).createAccount(any(UUID.class));
        }

        @Test
        @DisplayName("이메일이 중복되면 예외가 발생한다")
        void signup_fail_when_email_duplicated() {
            when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.signup(
                            new SignupCommand("test@example.com", "nickname", "pass1234")
                    )
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_EMAIL_DUPLICATED);
            verify(emailVerificationService)
                    .validateVerifiedEmail("test@example.com", VerificationPurpose.SIGNUP);
            verify(userRepository, never()).existsByNickname(anyString());
            verify(userRepository, never()).saveAndFlush(any(User.class));
            verify(groupService, never()).createDefaultGroup(any(User.class));
            verify(virtualAccountService, never()).createAccount(any(UUID.class));
        }

        @Test
        @DisplayName("닉네임이 중복되면 예외가 발생한다")
        void signup_fail_when_nickname_duplicated() {
            when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
            when(userRepository.existsByNickname("nickname")).thenReturn(true);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.signup(
                            new SignupCommand("test@example.com", "nickname", "pass1234")
                    )
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_NICKNAME_DUPLICATED);
            verify(emailVerificationService)
                    .validateVerifiedEmail("test@example.com", VerificationPurpose.SIGNUP);
            verify(userRepository, never()).saveAndFlush(any(User.class));
            verify(groupService, never()).createDefaultGroup(any(User.class));
            verify(virtualAccountService, never()).createAccount(any(UUID.class));
        }

        @Test
        @DisplayName("입력값이 null 또는 blank면 validation 예외가 발생한다")
        void signup_fail_when_input_invalid() {
            BusinessException nullException = assertThrows(
                    BusinessException.class,
                    () -> authService.signup(
                            new SignupCommand(null, "nickname", "pass1234")
                    )
            );

            BusinessException blankException = assertThrows(
                    BusinessException.class,
                    () -> authService.signup(
                            new SignupCommand("   ", "nickname", "pass1234")
                    )
            );

            assertAll(
                    () -> assertThat(nullException.getErrorCode()).isEqualTo(ErrorCode.AUTH_VALIDATION_FAILED),
                    () -> assertThat(blankException.getErrorCode()).isEqualTo(ErrorCode.AUTH_VALIDATION_FAILED)
            );

            verify(userRepository, never()).save(any(User.class));
            verify(groupService, never()).createDefaultGroup(any(User.class));
            verify(virtualAccountService, never()).createAccount(any(UUID.class));
        }

        @Test
        @DisplayName("DB 이메일 unique 제약 위반 시 이메일 중복 예외로 변환한다")
        void signup_fail_when_email_constraint_violated() {
            when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
            when(userRepository.existsByNickname("nickname")).thenReturn(false);
            when(passwordEncoder.encode("pass1234")).thenReturn("encoded-password");

            ConstraintViolationException cause =
                    new ConstraintViolationException("constraint violated", new SQLException(), "uk_users_email");

            when(userRepository.saveAndFlush(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("db error", cause));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.signup(
                            new SignupCommand("test@example.com", "nickname", "pass1234")
                    )
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_EMAIL_DUPLICATED);
            verify(emailVerificationService)
                    .validateVerifiedEmail("test@example.com", VerificationPurpose.SIGNUP);
            verify(groupService, never()).createDefaultGroup(any(User.class));
            verify(virtualAccountService, never()).createAccount(any(UUID.class));
        }

        @Test
        @DisplayName("DB 닉네임 unique 제약 위반 시 닉네임 중복 예외로 변환한다")
        void signup_fail_when_nickname_constraint_violated() {
            when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
            when(userRepository.existsByNickname("nickname")).thenReturn(false);
            when(passwordEncoder.encode("pass1234")).thenReturn("encoded-password");

            ConstraintViolationException cause =
                    new ConstraintViolationException("constraint violated", new SQLException(), "uk_users_nickname");

            when(userRepository.saveAndFlush(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("db error", cause));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.signup(
                            new SignupCommand("test@example.com", "nickname", "pass1234")
                    )
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_NICKNAME_DUPLICATED);
            verify(emailVerificationService)
                    .validateVerifiedEmail("test@example.com", VerificationPurpose.SIGNUP);
            verify(groupService, never()).createDefaultGroup(any(User.class));
            verify(virtualAccountService, never()).createAccount(any(UUID.class));
        }
    }

    @Nested
    @DisplayName("resetPassword")
    class ResetPasswordTest {

        @Test
        @DisplayName("비밀번호 재설정에 성공한다")
        void resetPassword_success() {
            String email = "test@example.com";
            String newPassword = "newpass123";

            UUID userId = UUID.randomUUID();

            User user = User.createNormalAccount(
                    email,
                    "testuser",
                    "old-password"
            );

            ReflectionTestUtils.setField(user, "userId", userId);

            when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
            when(passwordEncoder.encode(newPassword)).thenReturn("encoded-password");

            authService.resetPassword(email, newPassword);

            verify(emailVerificationService)
                    .validateVerifiedEmail(email, VerificationPurpose.PASSWORD_RESET);
            verify(emailVerificationService)
                    .consumeVerifiedEmail(email, VerificationPurpose.PASSWORD_RESET);

            assertThat(user.getPassword()).isEqualTo("encoded-password");
        }

        @Test
        @DisplayName("이메일 인증이 안 되어 있으면 실패한다")
        void resetPassword_fail_when_not_verified() {
            String email = "test@example.com";

            doThrow(new BusinessException(ErrorCode.AUTH_EMAIL_NOT_VERIFIED))
                    .when(emailVerificationService)
                    .validateVerifiedEmail(email, VerificationPurpose.PASSWORD_RESET);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.resetPassword(email, "newpass123")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_EMAIL_NOT_VERIFIED);
            verify(userRepository, never()).findByEmail(anyString());
        }

        @Test
        @DisplayName("사용자가 존재하지 않으면 실패한다")
        void resetPassword_fail_when_user_not_found() {
            String email = "test@example.com";

            // 인증은 통과된 상태
            doNothing()
                    .when(emailVerificationService)
                    .validateVerifiedEmail(email, VerificationPurpose.PASSWORD_RESET);

            when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.resetPassword(email, "newpass123")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);

            verify(emailVerificationService)
                    .validateVerifiedEmail(email, VerificationPurpose.PASSWORD_RESET);
        }
    }

    @Nested
    @DisplayName("issueAccount")
    class IssueAccountTest {

        @Test
        @DisplayName("대회 계정 발급에 성공하면 홈 그룹과 가상계좌를 함께 생성한다")
        void issueAccount_success_contest() {
            UUID userId = UUID.randomUUID();

            when(userRepository.existsByEmail("contest@example.com")).thenReturn(false);
            when(userRepository.existsByNickname("contest-user")).thenReturn(false);
            when(passwordEncoder.encode("pass1234")).thenReturn("encoded-password");

            User savedUser = User.createIssuedAccount(
                    "contest@example.com",
                    "contest-user",
                    "encoded-password",
                    UserAccountType.CONTEST
            );
            ReflectionTestUtils.setField(savedUser, "userId", userId);

            Group homeGroup = Group.createHomeGroup("contest-user의 그룹");
            ReflectionTestUtils.setField(homeGroup, "id", 100L);

            when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
            when(groupService.createDefaultGroup(savedUser)).thenReturn(homeGroup);

            IssuedAccountInfo result = authService.issueAccount(
                    new IssueAccountCommand(
                            "  CONTEST@Example.com  ",
                            "  contest-user  ",
                            "pass1234",
                            UserAccountType.CONTEST,
                            null
                    )
            );

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).saveAndFlush(captor.capture());
            verify(groupService).createDefaultGroup(savedUser);
            verify(virtualAccountService).createAccount(userId);
            verify(emailVerificationService, never()).validateVerifiedEmail(anyString(), any());
            verify(emailVerificationService, never()).consumeVerifiedEmail(anyString(), any());

            User capturedUser = captor.getValue();
            assertAll(
                    () -> assertThat(capturedUser.getEmail()).isEqualTo("contest@example.com"),
                    () -> assertThat(capturedUser.getNickname()).isEqualTo("contest-user"),
                    () -> assertThat(capturedUser.getPassword()).isEqualTo("encoded-password"),
                    () -> assertThat(capturedUser.getAccountType()).isEqualTo(UserAccountType.CONTEST),
                    () -> assertThat(result.userId()).isEqualTo(userId),
                    () -> assertThat(result.email()).isEqualTo("contest@example.com"),
                    () -> assertThat(result.nickname()).isEqualTo("contest-user"),
                    () -> assertThat(result.accountType()).isEqualTo(UserAccountType.CONTEST),
                    () -> assertThat(result.activeGroupId()).isEqualTo(100L),
                    () -> assertThat(result.activeGroupName()).isEqualTo("contest-user의 그룹")
            );
        }

        @Test
        @DisplayName("비즈니스 계정 발급에 성공한다")
        void issueAccount_success_business() {
            UUID userId = UUID.randomUUID();

            when(userRepository.existsByEmail("business@example.com")).thenReturn(false);
            when(userRepository.existsByNickname("business-user")).thenReturn(false);
            when(passwordEncoder.encode("pass1234")).thenReturn("encoded-password");

            User savedUser = User.createIssuedAccount(
                    "business@example.com",
                    "business-user",
                    "encoded-password",
                    UserAccountType.BUSINESS
            );
            ReflectionTestUtils.setField(savedUser, "userId", userId);
            Group homeGroup = Group.createHomeGroup("business-user의 그룹");
            ReflectionTestUtils.setField(homeGroup, "id", 100L);

            when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
            when(groupService.createDefaultGroup(savedUser)).thenReturn(homeGroup);

            IssuedAccountInfo result = authService.issueAccount(
                    new IssueAccountCommand(
                            "business@example.com",
                            "business-user",
                            "pass1234",
                            UserAccountType.BUSINESS,
                            null
                    )
            );

            assertThat(result.accountType()).isEqualTo(UserAccountType.BUSINESS);
            verify(virtualAccountService).createAccount(userId);
        }

        @Test
        @DisplayName("대상 그룹이 있으면 홈 그룹 생성 후 지정 공유 그룹에 배정한다")
        void issueAccount_success_with_target_group() {
            UUID userId = UUID.randomUUID();
            Long targetGroupId = 10L;

            when(userRepository.existsByEmail("contest@example.com")).thenReturn(false);
            when(userRepository.existsByNickname("contest-user")).thenReturn(false);
            when(passwordEncoder.encode("pass1234")).thenReturn("encoded-password");

            User savedUser = User.createIssuedAccount(
                    "contest@example.com",
                    "contest-user",
                    "encoded-password",
                    UserAccountType.CONTEST
            );
            ReflectionTestUtils.setField(savedUser, "userId", userId);

            Group homeGroup = Group.createHomeGroup("contest-user의 그룹");
            ReflectionTestUtils.setField(homeGroup, "id", 100L);

            when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
            when(groupService.createDefaultGroup(savedUser)).thenReturn(homeGroup);
            when(groupService.assignUserToSharedGroup(userId, targetGroupId))
                    .thenReturn(new GroupMemberInfo(
                            userId,
                            targetGroupId,
                            "대회 A반",
                            "contest-user",
                            "MEMBER"
                    ));

            IssuedAccountInfo result = authService.issueAccount(
                    new IssueAccountCommand(
                            "contest@example.com",
                            "contest-user",
                            "pass1234",
                            UserAccountType.CONTEST,
                            targetGroupId
                    )
            );

            InOrder inOrder = inOrder(groupService, virtualAccountService);
            inOrder.verify(groupService).createDefaultGroup(savedUser);
            inOrder.verify(virtualAccountService).createAccount(userId);
            inOrder.verify(groupService).assignUserToSharedGroup(userId, targetGroupId);

            assertAll(
                    () -> assertThat(result.activeGroupId()).isEqualTo(targetGroupId),
                    () -> assertThat(result.activeGroupName()).isEqualTo("대회 A반")
            );
        }

        @Test
        @DisplayName("NORMAL 계정은 관리자 발급 API로 생성할 수 없다")
        void issueAccount_fail_when_account_type_normal() {
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.issueAccount(
                            new IssueAccountCommand(
                                    "normal@example.com",
                                    "normal-user",
                                    "pass1234",
                                    UserAccountType.NORMAL,
                                    null
                            )
                    )
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_VALIDATION_FAILED);
            verify(userRepository, never()).saveAndFlush(any(User.class));
        }

        @Test
        @DisplayName("이메일이 중복되면 예외가 발생한다")
        void issueAccount_fail_when_email_duplicated() {
            when(userRepository.existsByEmail("contest@example.com")).thenReturn(true);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.issueAccount(
                            new IssueAccountCommand(
                                    "contest@example.com",
                                    "contest-user",
                                    "pass1234",
                                    UserAccountType.CONTEST,
                                    null
                            )
                    )
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_EMAIL_DUPLICATED);
            verify(userRepository, never()).saveAndFlush(any(User.class));
        }

        @Test
        @DisplayName("닉네임이 중복되면 예외가 발생한다")
        void issueAccount_fail_when_nickname_duplicated() {
            when(userRepository.existsByEmail("contest@example.com")).thenReturn(false);
            when(userRepository.existsByNickname("contest-user")).thenReturn(true);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.issueAccount(
                            new IssueAccountCommand(
                                    "contest@example.com",
                                    "contest-user",
                                    "pass1234",
                                    UserAccountType.CONTEST,
                                    null
                            )
                    )
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_NICKNAME_DUPLICATED);
            verify(userRepository, never()).saveAndFlush(any(User.class));
        }
    }

    @Nested
    @DisplayName("login")
    class LoginTest {

        @Test
        @DisplayName("로그인에 성공하면 access token, refresh token을 발급하고 저장한다")
        void login_success() {
            UUID userId = UUID.randomUUID();
            OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(14);

            User user = User.createNormalAccount(
                    "test@example.com",
                    "testuser",
                    "encoded-password"
            );

            ReflectionTestUtils.setField(user, "userId", userId);
            ReflectionTestUtils.setField(user, "status", UserStatus.ACTIVE);

            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("pass1234", "encoded-password")).thenReturn(true);
            when(jwtProvider.generateAccessToken(userId)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(userId)).thenReturn("refresh-token");
            when(jwtProvider.getExpiration("refresh-token")).thenReturn(expiresAt);
            when(refreshTokenRepository.findById(userId)).thenReturn(Optional.empty());

            LoginInfo result = authService.login("test@example.com", "pass1234");

            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(captor.capture());

            RefreshToken savedToken = captor.getValue();

            assertAll(
                    () -> assertThat(result.userId()).isEqualTo(userId),
                    () -> assertThat(result.nickname()).isEqualTo("testuser"),
                    () -> assertThat(result.accessToken()).isEqualTo("access-token"),
                    () -> assertThat(result.refreshToken()).isEqualTo("refresh-token"),
                    () -> assertThat(savedToken.getUserId()).isEqualTo(userId),
                    () -> assertThat(savedToken.getToken()).isEqualTo("refresh-token"),
                    () -> assertThat(savedToken.getExpiresAt()).isEqualTo(expiresAt),
                    () -> assertThat(savedToken.isRevoked()).isFalse()
            );
            verify(questProgressService).handleEvent(userId, QuestEventType.LOGIN);
            verify(userQuestService).getOrIssueTodayUserQuests(userId);
        }

        @Test
        @DisplayName("퀘스트 반영이 실패해도 로그인은 정상적으로 성공한다")
        void login_success_even_when_quest_progress_update_fails() {
            UUID userId = UUID.randomUUID();
            OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(14);

            User user = User.createNormalAccount(
                    "test@example.com",
                    "testuser",
                    "encoded-password"
            );

            ReflectionTestUtils.setField(user, "userId", userId);
            ReflectionTestUtils.setField(user, "status", UserStatus.ACTIVE);

            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("pass1234", "encoded-password")).thenReturn(true);
            when(jwtProvider.generateAccessToken(userId)).thenReturn("access-token");
            when(jwtProvider.generateRefreshToken(userId)).thenReturn("refresh-token");
            when(jwtProvider.getExpiration("refresh-token")).thenReturn(expiresAt);
            when(refreshTokenRepository.findById(userId)).thenReturn(Optional.empty());
            doThrow(new RuntimeException("quest failed"))
                    .when(questProgressService).handleEvent(userId, QuestEventType.LOGIN);

            LoginInfo result = authService.login("test@example.com", "pass1234");

            verify(refreshTokenRepository).save(any(RefreshToken.class));
            verify(questProgressService).handleEvent(userId, QuestEventType.LOGIN);
            verify(userQuestService).getOrIssueTodayUserQuests(userId);

            assertAll(
                    () -> assertThat(result.userId()).isEqualTo(userId),
                    () -> assertThat(result.nickname()).isEqualTo("testuser"),
                    () -> assertThat(result.accessToken()).isEqualTo("access-token"),
                    () -> assertThat(result.refreshToken()).isEqualTo("refresh-token")
            );
        }

        @Test
        @DisplayName("이메일이 존재하지 않으면 로그인 실패 예외가 발생한다")
        void login_fail_when_user_not_found() {
            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.login("test@example.com", "pass12324")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_LOGIN_FAILED);
            verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("비밀번호가 일치하지 않으면 로그인 실패 예외가 발생한다")
        void login_fail_when_password_not_matched() {
            UUID userId = UUID.randomUUID();

            User user = User.createNormalAccount(
                    "test@example.com",
                    "testuser",
                    "encoded-password"
            );

            ReflectionTestUtils.setField(user, "userId", userId);
            ReflectionTestUtils.setField(user, "status", UserStatus.ACTIVE);

            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong-pass", "encoded-password")).thenReturn(false);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.login("test@example.com", "wrong-pass")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_LOGIN_FAILED);
            verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("계정이 잠금 상태면 ACCOUNT_LOCKED 예외가 발생한다")
        void login_fail_when_account_locked() {
            UUID userId = UUID.randomUUID();

            User user = User.createNormalAccount(
                    "test@example.com",
                    "testuser",
                    "encoded-password"
            );

            ReflectionTestUtils.setField(user, "userId", userId);
            ReflectionTestUtils.setField(user, "status", UserStatus.LOCKED);

            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.login("test@example.com", "pass1234")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED);
            verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("입력값이 null 또는 blank면 validation 예외가 발생한다")
        void login_fail_when_input_invalid() {
            BusinessException nullException = assertThrows(
                    BusinessException.class,
                    () -> authService.login(null, "pass1234")
            );

            BusinessException blankException = assertThrows(
                    BusinessException.class,
                    () -> authService.login("   ", "pass1234")
            );

            assertAll(
                    () -> assertThat(nullException.getErrorCode()).isEqualTo(ErrorCode.AUTH_VALIDATION_FAILED),
                    () -> assertThat(blankException.getErrorCode()).isEqualTo(ErrorCode.AUTH_VALIDATION_FAILED)
            );

            verify(userRepository, never()).findByEmail(anyString());
            verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        }
    }

    @Nested
    @DisplayName("changePassword")
    class ChangePasswordTest {

        @Test
        @DisplayName("비밀번호 변경에 성공한다")
        void changePassword_success() {
            UUID userId = UUID.randomUUID();

            User user = User.createNormalAccount(
                    "test@example.com",
                    "testuser",
                    "encoded-old-password"
            );

            ReflectionTestUtils.setField(user, "userId", userId);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("oldpass123", "encoded-old-password")).thenReturn(true);
            when(passwordEncoder.matches("newpass123", "encoded-old-password")).thenReturn(false);
            when(passwordEncoder.encode("newpass123")).thenReturn("encoded-new-password");

            authService.changePassword(userId, "oldpass123", "newpass123");

            assertThat(user.getPassword()).isEqualTo("encoded-new-password");
            verify(passwordEncoder).encode("newpass123");
        }

        @Test
        @DisplayName("현재 비밀번호가 일치하지 않으면 실패한다")
        void changePassword_fail_when_password_mismatch() {
            UUID userId = UUID.randomUUID();

            User user = User.createNormalAccount(
                    "test@example.com",
                    "testuser",
                    "encoded-old-password"
            );

            ReflectionTestUtils.setField(user, "userId", userId);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrongpass", "encoded-old-password")).thenReturn(false);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.changePassword(userId, "wrongpass", "newpass123")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_PASSWORD_MISMATCH);
        }

        @Test
        @DisplayName("새 비밀번호가 기존과 같으면 실패한다")
        void changePassword_fail_when_same_password() {
            UUID userId = UUID.randomUUID();

            User user = User.createNormalAccount(
                    "test@example.com",
                    "testuser",
                    "encoded-old-password"
            );

            ReflectionTestUtils.setField(user, "userId", userId);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("oldpass123", "encoded-old-password")).thenReturn(true);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.changePassword(userId, "oldpass123", "oldpass123")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_PASSWORD_SAME_AS_OLD);
        }

        @Test
        @DisplayName("사용자가 존재하지 않으면 실패한다")
        void changePassword_fail_when_user_not_found() {
            UUID userId = UUID.randomUUID();

            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.changePassword(userId, "oldpass123", "newpass123")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("withdraw")
    class WithdrawTest {

        @Test
        @DisplayName("회원 탈퇴 시 ACTIVE 그룹 멤버가 모두 비활성화되고 refresh token이 revoke된다")
        void withdraw_success() {
            UUID userId = UUID.randomUUID();

            User user = User.createNormalAccount(
                    "test@example.com",
                    "testuser",
                    "encoded-password"
            );

            ReflectionTestUtils.setField(user, "userId", userId);

            GroupMember member1 = mock(GroupMember.class);
            GroupMember member2 = mock(GroupMember.class);
            RefreshToken refreshToken = mock(RefreshToken.class);

            when(userRepository.findByIdForUpdate(userId))
                    .thenReturn(Optional.of(user));
            when(passwordEncoder.matches("pass1234", "encoded-password"))
                    .thenReturn(true);
            when(groupMemberRepository.findAllByUser_UserIdAndStatus(
                    userId,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(List.of(member1, member2));
            when(refreshTokenRepository.findById(userId))
                    .thenReturn(Optional.of(refreshToken));

            authService.withdraw(userId, "pass1234");

            verify(member1).deactivate();
            verify(member2).deactivate();
            verify(refreshToken).revoke();
            assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
            assertThat(user.getHomeGroup()).isNull();
        }

        @Test
        @DisplayName("shared group 리더가 탈퇴하면 다음 ACTIVE 멤버에게 리더가 승계된다")
        void withdraw_transfers_leadership_when_leader_leaves_shared_group() {
            UUID userId = UUID.randomUUID();

            User user = User.createNormalAccount(
                    "test@example.com",
                    "testuser",
                    "encoded-password"
            );

            ReflectionTestUtils.setField(user, "userId", userId);

            Group sharedGroup = Group.createSharedGroup("공유 그룹");

            GroupMember leaderMember = mock(GroupMember.class);
            GroupMember nextLeader = mock(GroupMember.class);

            when(userRepository.findByIdForUpdate(userId))
                    .thenReturn(Optional.of(user));
            when(passwordEncoder.matches("pass1234", "encoded-password"))
                    .thenReturn(true);

            when(groupMemberRepository.findAllByUser_UserIdAndStatus(
                    userId,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(List.of(leaderMember));

            when(leaderMember.isLeader()).thenReturn(true);
            when(leaderMember.getGroup()).thenReturn(sharedGroup);

            when(groupMemberRepository.countByGroupAndStatus(
                    sharedGroup,
                    GroupMember.GroupMemberStatus.ACTIVE
            )).thenReturn(1L);

            when(groupMemberRepository.findFirstByGroupAndStatusAndUser_UserIdNotOrderByIdAsc(
                    sharedGroup,
                    GroupMember.GroupMemberStatus.ACTIVE,
                    userId
            )).thenReturn(Optional.of(nextLeader));

            when(refreshTokenRepository.findById(userId))
                    .thenReturn(Optional.empty());

            authService.withdraw(userId, "pass1234");

            InOrder inOrder = inOrder(leaderMember, groupMemberRepository, nextLeader);

            inOrder.verify(leaderMember).changeRoleToMember();
            inOrder.verify(leaderMember).deactivate();
            inOrder.verify(groupMemberRepository).flush();
            inOrder.verify(nextLeader).changeRoleToLeader();

            assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        }

        @Test
        @DisplayName("비밀번호가 일치하지 않으면 탈퇴에 실패한다")
        void withdraw_fail_when_password_mismatch() {
            UUID userId = UUID.randomUUID();

            User user = User.createNormalAccount(
                    "test@example.com",
                    "testuser",
                    "encoded-password"
            );

            ReflectionTestUtils.setField(user, "userId", userId);

            when(userRepository.findByIdForUpdate(userId))
                    .thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong-password", "encoded-password"))
                    .thenReturn(false);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.withdraw(userId, "wrong-password")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_PASSWORD_MISMATCH);
            verify(groupMemberRepository, never()).findAllByUser_UserIdAndStatus(any(), any());
            verify(refreshTokenRepository, never()).findById(any());
        }
    }
}
