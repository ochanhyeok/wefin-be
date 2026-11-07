package com.solv.wefin.web.admin.dto;

import com.solv.wefin.domain.auth.dto.IssuedAccountInfo;
import com.solv.wefin.domain.auth.entity.UserAccountType;

import java.util.UUID;

public record IssueAccountResponse(
        UUID userId,
        String email,
        String nickname,
        UserAccountType accountType,
        Long activeGroupId,
        String activeGroupName
) {
    public static IssueAccountResponse from(IssuedAccountInfo info) {
        return new IssueAccountResponse(
                info.userId(),
                info.email(),
                info.nickname(),
                info.accountType(),
                info.activeGroupId(),
                info.activeGroupName()
        );
    }
}
