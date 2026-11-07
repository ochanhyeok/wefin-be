package com.solv.wefin.domain.admin.service;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.entity.UserStatus;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAuthorizationService {

    private final UserRepository userRepository;

    public void requireAdmin(UUID userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_UNAUTHORIZED));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }

        if (!user.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_FORBIDDEN);
        }
    }
}
