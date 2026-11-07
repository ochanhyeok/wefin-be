package com.solv.wefin.domain.auth.service;

import com.solv.wefin.domain.auth.entity.RefreshToken;
import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.entity.UserStatus;
import com.solv.wefin.domain.auth.repository.RefreshTokenRepository;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.group.service.GroupService;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.global.config.security.JwtProvider;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthTokenServiceTest {

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
    private QuestProgressService questProgressService;

    @InjectMocks
    private AuthService authService;

    private User activeUser(UUID userId) {
        User user = User.createNormalAccount("test@example.com", "testuser", "password");
        ReflectionTestUtils.setField(user, "userId", userId);
        ReflectionTestUtils.setField(user, "status", UserStatus.ACTIVE);
        return user;
    }

    @Nested
    @DisplayName("refresh")
    class RefreshTest {

        @Test
        @DisplayName("유효한 refresh token이면 새 access token을 발급한다")
        void refresh_success() {
            UUID userId = UUID.randomUUID();
            OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(14);

            RefreshToken savedToken = RefreshToken.builder()
                    .userId(userId)
                    .token("refresh-token")
                    .expiresAt(expiresAt)
                    .build();

            when(jwtProvider.isValid("refresh-token")).thenReturn(true);
            when(jwtProvider.getTokenType("refresh-token")).thenReturn("refresh");
            when(jwtProvider.getUserId("refresh-token")).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
            when(refreshTokenRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(savedToken));
            when(jwtProvider.generateAccessToken(userId)).thenReturn("new-access-token");

            String result = authService.refresh("refresh-token");

            assertThat(result).isEqualTo("new-access-token");
            verify(jwtProvider).generateAccessToken(userId);
        }

        @Test
        @DisplayName("토큰이 유효하지 않으면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void refresh_fail_when_token_invalid() {
            when(jwtProvider.isValid("invalid-token")).thenReturn(false);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.refresh("invalid-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
            verify(userRepository, never()).findById(any(UUID.class));
            verify(refreshTokenRepository, never()).findByUserIdForUpdate(any(UUID.class));
            verify(jwtProvider, never()).generateAccessToken(any(UUID.class));
        }

        @Test
        @DisplayName("refresh 토큰 타입이 아니면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void refresh_fail_when_token_type_is_not_refresh() {
            when(jwtProvider.isValid("access-token")).thenReturn(true);
            when(jwtProvider.getTokenType("access-token")).thenReturn("access");

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.refresh("access-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
            verify(userRepository, never()).findById(any(UUID.class));
            verify(refreshTokenRepository, never()).findByUserIdForUpdate(any(UUID.class));
            verify(jwtProvider, never()).generateAccessToken(any(UUID.class));
        }

        @Test
        @DisplayName("DB에 저장된 refresh token이 없으면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void refresh_fail_when_saved_token_not_found() {
            UUID userId = UUID.randomUUID();

            when(jwtProvider.isValid("refresh-token")).thenReturn(true);
            when(jwtProvider.getTokenType("refresh-token")).thenReturn("refresh");
            when(jwtProvider.getUserId("refresh-token")).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
            when(refreshTokenRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.refresh("refresh-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
            verify(jwtProvider, never()).generateAccessToken(any(UUID.class));
        }

        @Test
        @DisplayName("DB에 저장된 토큰 값과 다르면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void refresh_fail_when_token_not_matched() {
            UUID userId = UUID.randomUUID();
            OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(14);

            RefreshToken savedToken = RefreshToken.builder()
                    .userId(userId)
                    .token("different-token")
                    .expiresAt(expiresAt)
                    .build();

            when(jwtProvider.isValid("refresh-token")).thenReturn(true);
            when(jwtProvider.getTokenType("refresh-token")).thenReturn("refresh");
            when(jwtProvider.getUserId("refresh-token")).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
            when(refreshTokenRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(savedToken));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.refresh("refresh-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
            verify(jwtProvider, never()).generateAccessToken(any(UUID.class));
        }

        @Test
        @DisplayName("revoked 된 토큰이면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void refresh_fail_when_token_revoked() {
            UUID userId = UUID.randomUUID();
            OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(14);

            RefreshToken savedToken = RefreshToken.builder()
                    .userId(userId)
                    .token("refresh-token")
                    .expiresAt(expiresAt)
                    .build();
            ReflectionTestUtils.setField(savedToken, "revoked", true);

            when(jwtProvider.isValid("refresh-token")).thenReturn(true);
            when(jwtProvider.getTokenType("refresh-token")).thenReturn("refresh");
            when(jwtProvider.getUserId("refresh-token")).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
            when(refreshTokenRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(savedToken));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.refresh("refresh-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
            verify(jwtProvider, never()).generateAccessToken(any(UUID.class));
        }

        @Test
        @DisplayName("만료된 refresh token이면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void refresh_fail_when_token_expired() {
            UUID userId = UUID.randomUUID();
            OffsetDateTime expiresAt = OffsetDateTime.now().minusMinutes(1);

            RefreshToken savedToken = RefreshToken.builder()
                    .userId(userId)
                    .token("refresh-token")
                    .expiresAt(expiresAt)
                    .build();

            when(jwtProvider.isValid("refresh-token")).thenReturn(true);
            when(jwtProvider.getTokenType("refresh-token")).thenReturn("refresh");
            when(jwtProvider.getUserId("refresh-token")).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
            when(refreshTokenRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(savedToken));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.refresh("refresh-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
            verify(jwtProvider, never()).generateAccessToken(any(UUID.class));
        }
    }

    @Nested
    @DisplayName("logout")
    class LogoutTest {

        @Test
        @DisplayName("유효한 refresh token이면 revoke 처리한다")
        void logout_success() {
            UUID userId = UUID.randomUUID();
            OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(14);

            RefreshToken savedToken = RefreshToken.builder()
                    .userId(userId)
                    .token("refresh-token")
                    .expiresAt(expiresAt)
                    .build();

            when(jwtProvider.isValid("refresh-token")).thenReturn(true);
            when(jwtProvider.getTokenType("refresh-token")).thenReturn("refresh");
            when(jwtProvider.getUserId("refresh-token")).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
            when(refreshTokenRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(savedToken));

            authService.logout(userId, "refresh-token");

            assertThat(savedToken.isRevoked()).isTrue();
        }

        @Test
        @DisplayName("토큰이 유효하지 않으면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void logout_fail_when_token_invalid() {
            UUID userId = UUID.randomUUID();

            when(jwtProvider.isValid("invalid-token")).thenReturn(false);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.logout(userId, "invalid-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
            verify(userRepository, never()).findById(any(UUID.class));
            verify(refreshTokenRepository, never()).findByUserIdForUpdate(any(UUID.class));
        }

        @Test
        @DisplayName("refresh 토큰 타입이 아니면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void logout_fail_when_token_type_is_not_refresh() {
            UUID userId = UUID.randomUUID();

            when(jwtProvider.isValid("access-token")).thenReturn(true);
            when(jwtProvider.getTokenType("access-token")).thenReturn("access");

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.logout(userId, "access-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
            verify(userRepository, never()).findById(any(UUID.class));
            verify(refreshTokenRepository, never()).findByUserIdForUpdate(any(UUID.class));
        }

        @Test
        @DisplayName("사용자가 존재하지 않으면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void logout_fail_when_user_not_found() {
            UUID userId = UUID.randomUUID();

            when(jwtProvider.isValid("refresh-token")).thenReturn(true);
            when(jwtProvider.getTokenType("refresh-token")).thenReturn("refresh");
            when(jwtProvider.getUserId("refresh-token")).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.logout(userId, "refresh-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
            verify(refreshTokenRepository, never()).findByUserIdForUpdate(any(UUID.class));
        }

        @Test
        @DisplayName("사용자가 ACTIVE 상태가 아니면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void logout_fail_when_user_not_active() {
            UUID userId = UUID.randomUUID();

            User user = User.createNormalAccount("test@example.com", "testuser", "password");
            ReflectionTestUtils.setField(user, "userId", userId);
            ReflectionTestUtils.setField(user, "status", UserStatus.LOCKED);

            when(jwtProvider.isValid("refresh-token")).thenReturn(true);
            when(jwtProvider.getTokenType("refresh-token")).thenReturn("refresh");
            when(jwtProvider.getUserId("refresh-token")).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.logout(userId, "refresh-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
            verify(refreshTokenRepository, never()).findByUserIdForUpdate(any(UUID.class));
        }

        @Test
        @DisplayName("DB에 저장된 refresh token이 없으면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void logout_fail_when_saved_token_not_found() {
            UUID userId = UUID.randomUUID();

            when(jwtProvider.isValid("refresh-token")).thenReturn(true);
            when(jwtProvider.getTokenType("refresh-token")).thenReturn("refresh");
            when(jwtProvider.getUserId("refresh-token")).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
            when(refreshTokenRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.logout(userId, "refresh-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
        }

        @Test
        @DisplayName("DB에 저장된 토큰 값과 다르면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void logout_fail_when_token_not_matched() {
            UUID userId = UUID.randomUUID();
            OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(14);

            RefreshToken savedToken = RefreshToken.builder()
                    .userId(userId)
                    .token("different-token")
                    .expiresAt(expiresAt)
                    .build();

            when(jwtProvider.isValid("refresh-token")).thenReturn(true);
            when(jwtProvider.getTokenType("refresh-token")).thenReturn("refresh");
            when(jwtProvider.getUserId("refresh-token")).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
            when(refreshTokenRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(savedToken));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.logout(userId, "refresh-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
        }

        @Test
        @DisplayName("revoked 된 토큰이면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void logout_fail_when_token_revoked() {
            UUID userId = UUID.randomUUID();
            OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(14);

            RefreshToken savedToken = RefreshToken.builder()
                    .userId(userId)
                    .token("refresh-token")
                    .expiresAt(expiresAt)
                    .build();
            ReflectionTestUtils.setField(savedToken, "revoked", true);

            when(jwtProvider.isValid("refresh-token")).thenReturn(true);
            when(jwtProvider.getTokenType("refresh-token")).thenReturn("refresh");
            when(jwtProvider.getUserId("refresh-token")).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
            when(refreshTokenRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(savedToken));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.logout(userId, "refresh-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
        }

        @Test
        @DisplayName("만료된 refresh token이면 AUTH_INVALID_TOKEN 예외가 발생한다")
        void logout_fail_when_token_expired() {
            UUID userId = UUID.randomUUID();
            OffsetDateTime expiresAt = OffsetDateTime.now().minusMinutes(1);

            RefreshToken savedToken = RefreshToken.builder()
                    .userId(userId)
                    .token("refresh-token")
                    .expiresAt(expiresAt)
                    .build();

            when(jwtProvider.isValid("refresh-token")).thenReturn(true);
            when(jwtProvider.getTokenType("refresh-token")).thenReturn("refresh");
            when(jwtProvider.getUserId("refresh-token")).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
            when(refreshTokenRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(savedToken));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> authService.logout(userId, "refresh-token")
            );

            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
        }
    }
}
