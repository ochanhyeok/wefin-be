package com.solv.wefin.global.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solv.wefin.domain.admin.service.AdminAuthorizationService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminAuthorizationFilterTest {

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private AdminAuthorizationFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<AdminAuthorizationService> adminAuthorizationServiceProvider = mock(ObjectProvider.class);
        lenient().when(adminAuthorizationServiceProvider.getIfAvailable()).thenReturn(adminAuthorizationService);
        filter = new AdminAuthorizationFilter(adminAuthorizationServiceProvider, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("admin accounts 경로가 아니면 권한 검사를 건너뛴다")
    void doFilterInternal_skips_non_admin_path() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/news");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(adminAuthorizationService, never()).requireAdmin(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("admin batch 경로는 권한 검사를 건너뛴다")
    void doFilterInternal_skips_admin_batch_path() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/batch/news");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(adminAuthorizationService, never()).requireAdmin(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("admin accounts 경로에서 인증 정보가 없으면 401을 반환한다")
    void doFilterInternal_returns_unauthorized_when_anonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTH_UNAUTHORIZED");
    }

    @Test
    @DisplayName("admin accounts 경로에서 일반 유저면 403을 반환한다")
    void doFilterInternal_returns_forbidden_when_not_admin() throws Exception {
        UUID userId = UUID.randomUUID();
        setAuthentication(userId);

        doThrow(new BusinessException(ErrorCode.ADMIN_FORBIDDEN))
                .when(adminAuthorizationService)
                .requireAdmin(userId);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_FORBIDDEN");
    }

    @Test
    @DisplayName("admin accounts 경로에서 비활성 유저면 401을 반환한다")
    void doFilterInternal_returns_unauthorized_when_user_not_active() throws Exception {
        UUID userId = UUID.randomUUID();
        setAuthentication(userId);

        doThrow(new BusinessException(ErrorCode.AUTH_UNAUTHORIZED))
                .when(adminAuthorizationService)
                .requireAdmin(userId);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTH_UNAUTHORIZED");
    }

    @Test
    @DisplayName("admin accounts 경로에서 ADMIN 유저면 요청을 통과시킨다")
    void doFilterInternal_allows_admin() throws Exception {
        UUID userId = UUID.randomUUID();
        setAuthentication(userId);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(adminAuthorizationService).requireAdmin(userId);
    }

    private void setAuthentication(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        AuthorityUtils.NO_AUTHORITIES
                )
        );
    }
}
