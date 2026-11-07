package com.solv.wefin.domain.auth.dto;

import com.solv.wefin.domain.auth.entity.UserAccountType;

import java.util.UUID;

public record IssuedAccountInfo(
        UUID userId,
        String email,
        String nickname,
        UserAccountType accountType,
        Long activeGroupId,
        String activeGroupName
) {
}
