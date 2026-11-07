package com.solv.wefin.web.admin;

import com.solv.wefin.domain.auth.dto.IssueAccountCommand;
import com.solv.wefin.domain.auth.dto.IssuedAccountInfo;
import com.solv.wefin.domain.auth.service.AuthService;
import com.solv.wefin.global.common.ApiResponse;
import com.solv.wefin.web.admin.dto.IssueAccountRequest;
import com.solv.wefin.web.admin.dto.IssueAccountResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/accounts")
@RequiredArgsConstructor
public class AccountAdminController {

    private final AuthService authService;

    @PostMapping
    public ApiResponse<IssueAccountResponse> issueAccount(
            @RequestBody @Valid IssueAccountRequest request
    ) {
        IssuedAccountInfo result = authService.issueAccount(
                new IssueAccountCommand(
                        request.email(),
                        request.nickname(),
                        request.password(),
                        request.accountType(),
                        request.targetGroupId()
                )
        );

        return ApiResponse.success(201, IssueAccountResponse.from(result));
    }
}
