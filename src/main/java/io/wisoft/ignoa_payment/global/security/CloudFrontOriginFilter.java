package io.wisoft.ignoa_payment.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// CloudFront를 거친 요청만 웹훅 경로에 들어오게 한다(ignoa-api의 같은 필터와 동일한 방식).
@Slf4j
@Component
public class CloudFrontOriginFilter extends OncePerRequestFilter {

    private static final String ORIGIN_HEADER = "X-Origin-Verify";
    private static final String PUBLIC_PATH_PREFIX = "/payments/";

    private final boolean enabled;
    private final byte[] secret;

    public CloudFrontOriginFilter(
            @Value("${ignoa.cloudfront-origin.enabled:false}") boolean enabled,
            @Value("${ignoa.cloudfront-origin.secret:}") String secret) {

        if (enabled && !StringUtils.hasText(secret)) {
            throw new IllegalStateException("CloudFront Origin 비밀값이 설정되지 않았습니다.");
        }

        this.enabled = enabled;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled
                || !request.getRequestURI().startsWith(PUBLIC_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestSecret = request.getHeader(ORIGIN_HEADER);

        if (requestSecret == null
                || !MessageDigest.isEqual(secret, requestSecret.getBytes(StandardCharsets.UTF_8))) {
            log.debug("CloudFront Origin 검증 실패: method={}, uri={}, remoteAddress={}",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
