package com.solv.wefin.web.admin;

import com.solv.wefin.domain.auth.dto.IssueAccountCommand;
import com.solv.wefin.domain.auth.dto.IssuedAccountInfo;
import com.solv.wefin.domain.auth.entity.UserAccountType;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.auth.service.AuthService;
import com.solv.wefin.domain.admin.service.AdminAuthorizationService;
import com.solv.wefin.global.config.security.JwtProvider;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import com.solv.wefin.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AccountAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminAuthorizationService adminAuthorizationService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JwtProvider jwtProvider;

    @Nested
    @DisplayName("POST /api/admin/accounts")
    class IssueAccountTest {

        @Test
        @DisplayName("관리자가 대회 계정을 발급하면 201과 생성 계정 정보를 반환한다")
        void issueAccount_success() throws Exception {
            UUID adminUserId = UUID.randomUUID();
            UUID issuedUserId = UUID.randomUUID();

            when(authService.issueAccount(any(IssueAccountCommand.class)))
                    .thenReturn(new IssuedAccountInfo(
                            issuedUserId,
                            "contest@example.com",
                            "contest-user",
                            UserAccountType.CONTEST,
                            10L,
                            "대회 A반"
                    ));

            mockMvc.perform(post("/api/admin/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "accountType": "CONTEST",
                                      "email": "contest@example.com",
                                      "password": "pass1234",
                                      "nickname": "contest-user",
                                      "targetGroupId": 10
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.data.userId").value(issuedUserId.toString()))
                    .andExpect(jsonPath("$.data.email").value("contest@example.com"))
                    .andExpect(jsonPath("$.data.nickname").value("contest-user"))
                    .andExpect(jsonPath("$.data.accountType").value("CONTEST"))
                    .andExpect(jsonPath("$.data.activeGroupId").value(10))
                    .andExpect(jsonPath("$.data.activeGroupName").value("대회 A반"));

            ArgumentCaptor<IssueAccountCommand> captor = ArgumentCaptor.forClass(IssueAccountCommand.class);
            verify(authService).issueAccount(captor.capture());

            IssueAccountCommand command = captor.getValue();
            assertThat(command.accountType()).isEqualTo(UserAccountType.CONTEST);
            assertThat(command.email()).isEqualTo("contest@example.com");
            assertThat(command.nickname()).isEqualTo("contest-user");
            assertThat(command.password()).isEqualTo("pass1234");
            assertThat(command.targetGroupId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("NORMAL 계정 발급 요청은 400을 반환한다")
        void issueAccount_fail_when_account_type_normal() throws Exception {
            when(authService.issueAccount(any(IssueAccountCommand.class)))
                    .thenThrow(new BusinessException(ErrorCode.AUTH_VALIDATION_FAILED));

            mockMvc.perform(post("/api/admin/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "accountType": "NORMAL",
                                      "email": "normal@example.com",
                                      "password": "pass1234",
                                      "nickname": "normal-user"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AUTH_VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("중복 이메일이면 409를 반환한다")
        void issueAccount_fail_when_email_duplicated() throws Exception {
            when(authService.issueAccount(any(IssueAccountCommand.class)))
                    .thenThrow(new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED));

            mockMvc.perform(post("/api/admin/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "accountType": "BUSINESS",
                                      "email": "business@example.com",
                                      "password": "pass1234",
                                      "nickname": "business-user"
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("AUTH_EMAIL_DUPLICATED"));
        }
    }
}
