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
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AdminAuthorizationFilter extends OncePerRequestFilter {

    private static final String ADMIN_ACCOUNTS_PATH_PREFIX = "/api/admin/accounts";

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
        String path = request.getRequestURI();
        return path.equals(ADMIN_ACCOUNTS_PATH_PREFIX)
                || path.startsWith(ADMIN_ACCOUNTS_PATH_PREFIX + "/");
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(errorCode)));
    }
}
