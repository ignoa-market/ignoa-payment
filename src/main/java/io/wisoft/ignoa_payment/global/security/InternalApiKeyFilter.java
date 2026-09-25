package io.wisoft.ignoa_payment.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wisoft.ignoa_payment.global.exception.ErrorCode;
import io.wisoft.ignoa_payment.global.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";
    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    private final byte[] apiKey;
    private final ObjectMapper objectMapper;

    public InternalApiKeyFilter(@Value("${ignoa.internal-api-key}") String apiKey, ObjectMapper objectMapper) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("내부 API 키가 설정되지 않았습니다.");
        }
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestKey = request.getHeader(HEADER);

        if (requestKey == null
                || !MessageDigest.isEqual(apiKey, requestKey.getBytes(StandardCharsets.UTF_8))) {
            log.debug("내부 API 키 검증 실패: method={}, uri={}, remoteAddress={}",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            ErrorCode errorCode = ErrorCode.INVALID_INTERNAL_API_KEY;
            response.setStatus(errorCode.getHttpStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
            return;
        }

        filterChain.doFilter(request, response);
    }
}
