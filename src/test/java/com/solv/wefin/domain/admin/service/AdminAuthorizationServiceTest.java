package com.solv.wefin.domain.admin.service;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.entity.UserRole;
import com.solv.wefin.domain.auth.entity.UserStatus;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuthorizationServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminAuthorizationService adminAuthorizationService;

    @Test
    @DisplayName("ACTIVE ADMIN 유저는 관리자 권한 검증을 통과한다")
    void requireAdmin_success() {
        UUID userId = UUID.randomUUID();
        User admin = User.createNormalAccount("admin@example.com", "admin", "password");
        ReflectionTestUtils.setField(admin, "userId", userId);
        ReflectionTestUtils.setField(admin, "role", UserRole.ADMIN);

        when(userRepository.findById(userId)).thenReturn(Optional.of(admin));

        assertDoesNotThrow(() -> adminAuthorizationService.requireAdmin(userId));
    }

    @Test
    @DisplayName("userId가 null이면 AUTH_UNAUTHORIZED 예외가 발생한다")
    void requireAdmin_fail_when_user_id_null() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adminAuthorizationService.requireAdmin(null)
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    @DisplayName("사용자를 찾을 수 없으면 AUTH_UNAUTHORIZED 예외가 발생한다")
    void requireAdmin_fail_when_user_not_found() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adminAuthorizationService.requireAdmin(userId)
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    @DisplayName("ACTIVE 상태가 아니면 AUTH_UNAUTHORIZED 예외가 발생한다")
    void requireAdmin_fail_when_user_not_active() {
        UUID userId = UUID.randomUUID();
        User admin = User.createNormalAccount("admin@example.com", "admin", "password");
        ReflectionTestUtils.setField(admin, "userId", userId);
        ReflectionTestUtils.setField(admin, "role", UserRole.ADMIN);
        ReflectionTestUtils.setField(admin, "status", UserStatus.LOCKED);

        when(userRepository.findById(userId)).thenReturn(Optional.of(admin));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adminAuthorizationService.requireAdmin(userId)
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    @DisplayName("ADMIN 권한이 없으면 ADMIN_FORBIDDEN 예외가 발생한다")
    void requireAdmin_fail_when_user_not_admin() {
        UUID userId = UUID.randomUUID();
        User user = User.createNormalAccount("user@example.com", "user", "password");
        ReflectionTestUtils.setField(user, "userId", userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adminAuthorizationService.requireAdmin(userId)
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ADMIN_FORBIDDEN);
    }
}
