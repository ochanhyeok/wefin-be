package com.solv.wefin.domain.user;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.user.dto.MyPageInfo;
import com.solv.wefin.domain.user.service.UserService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("마이페이지 조회 성공")
    void getMyPage_success() {
        // given
        UUID userId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-04-01T09:00:00+09:00");

        User user = User.createNormalAccount("user@example.com", "희민", "encodedPassword");

        ReflectionTestUtils.setField(user, "userId", userId);
        ReflectionTestUtils.setField(user, "createdAt", createdAt);

        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        // when
        MyPageInfo result = userService.getMyPage(userId);

        // then
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.email()).isEqualTo("user@example.com");
        assertThat(result.nickname()).isEqualTo("희민");
        assertThat(result.createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("존재하지 않는 사용자면 USER_NOT_FOUND 예외가 발생한다")
    void getMyPage_userNotFound() {
        // given
        UUID userId = UUID.randomUUID();
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.getMyPage(userId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("여러 userId로 사용자 목록을 조회한다")
    void findAllByIdIn_success() {
        // given
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();

        User user1 = User.createNormalAccount("user1@example.com", "유저1", "pw1");

        User user2 = User.createNormalAccount("user2@example.com", "유저2", "pw2");

        ReflectionTestUtils.setField(user1, "userId", userId1);
        ReflectionTestUtils.setField(user2, "userId", userId2);

        List<UUID> userIds = List.of(userId1, userId2);

        given(userRepository.findAllById(userIds)).willReturn(List.of(user1, user2));

        // when
        List<User> result = userService.findAllByIdIn(userIds);

        // then
        assertThat(result).containsExactly(user1, user2);
        then(userRepository).should(times(1)).findAllById(userIds);
    }
}
