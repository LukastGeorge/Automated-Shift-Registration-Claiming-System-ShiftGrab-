package com.shiftgrab.shared.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.shiftgrab.shared.error.ErrorCode;
import com.shiftgrab.shared.error.Problems;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes RFC 7807 bodies for 401/403 decided by the security filter chain, before any controller runs.
 * The bearer-token delegates still set the {@code WWW-Authenticate} header required by RFC 6750.
 */
class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();
    private final JsonMapper jsonMapper;

    ProblemDetailsSecurityHandler(JsonMapper jsonMapper) {
        // Same mixin Spring MVC applies, so extra properties such as "code" are top-level members.
        this.jsonMapper = jsonMapper.rebuild().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException, jakarta.servlet.ServletException {
        bearerEntryPoint.commence(request, response, authException);
        write(response, Problems.of(ErrorCode.UNAUTHORIZED, "A valid bearer token is required.",
                request.getRequestURI()));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException, jakarta.servlet.ServletException {
        bearerAccessDenied.handle(request, response, accessDeniedException);
        write(response, Problems.of(ErrorCode.FORBIDDEN, "You do not have permission to access this resource.",
                request.getRequestURI()));
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
