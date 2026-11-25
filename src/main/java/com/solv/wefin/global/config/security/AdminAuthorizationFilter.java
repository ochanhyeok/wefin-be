package com.solv.wefin.global.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solv.wefin.domain.admin.service.AdminAuthorizationService;
import com.solv.wefin.global.common.ApiResponse;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AdminAuthorizationFilter extends OncePerRequestFilter {

    /** 관리자 권한을 검사하는 경로. 그 밖의 /api/admin/** 는 SecurityConfig 에서 permitAll 이다 */
    private static final List<String> PROTECTED_PATH_PREFIXES = List.of(
            "/api/admin/accounts",
            "/api/admin/reconciliation"
    );

    private final ObjectProvider<AdminAuthorizationService> adminAuthorizationServiceProvider;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!isAdminPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID userId)) {
            writeError(response, ErrorCode.AUTH_UNAUTHORIZED);
            return;
        }

        AdminAuthorizationService adminAuthorizationService = adminAuthorizationServiceProvider.getIfAvailable();
        if (adminAuthorizationService == null) {
            writeError(response, ErrorCode.AUTH_UNAUTHORIZED);
            return;
        }

        try {
            adminAuthorizationService.requireAdmin(userId);

        } catch (BusinessException e) {
            writeError(response, e.getErrorCode());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAdminPath(HttpServletRequest request) {
        // SecurityConfig 의 경로 매칭과 같은 기준(context path 제외)으로 본다. 비루트 배포에서 검사가 빠지지 않게
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PROTECTED_PATH_PREFIXES.stream()
                .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(errorCode)));
    }
}
