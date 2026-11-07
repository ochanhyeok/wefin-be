package com.solv.wefin.domain.auth.dto;

import com.solv.wefin.domain.auth.entity.UserAccountType;

public record IssueAccountCommand(
        String email,
        String nickname,
        String password,
        UserAccountType accountType,
        Long targetGroupId
) {
}
