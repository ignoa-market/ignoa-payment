package io.wisoft.ignoa_payment.global.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

// getRequestURI()는 원본 경로라 "/%69nternal", "/internal;x=1"처럼 Spring MVC와 다르게 보인다.
// 필터는 MVC가 매칭하는 것과 같은 경로(디코딩, ';' 파라미터 제거)로 판정해야 우회되지 않는다.
final class RequestPaths {

    private static final UrlPathHelper URL_PATH_HELPER = new UrlPathHelper();

    private RequestPaths() {
    }

    static String normalized(HttpServletRequest request) {
        return URL_PATH_HELPER.getPathWithinApplication(request);
    }
}
