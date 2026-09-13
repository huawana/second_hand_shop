package shop.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import shop.common.ErrorCode;
import shop.common.Result;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证（401）的统一处理。
 *
 * <p>关键设计：<b>按请求类型分流</b>，而不是一刀切。
 * <ul>
 *   <li>接口请求（fetch / ajax）→ 返回 401 JSON，前端拦截器统一处理；</li>
 *   <li>页面请求（浏览器直接访问 URL）→ 302 跳登录页。
 *       给浏览器返回裸 JSON 会让用户看到一串错误文本，体验不可接受。</li>
 * </ul>
 * 这个「分流」正是 Spring Boot 默认行为（全部跳登录页）不够用的地方。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        String uri = request.getRequestURI();
        log.debug("未认证访问被拦截 uri={}", uri);

        if (RequestTypeUtils.isApiRequest(request)) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
            return;
        }

        // 页面请求：跳转到对应端的登录页
        String loginPage = uri.startsWith("/admin/") ? "/admin/login" : "/shop/login";
        response.sendRedirect(loginPage);
    }

    private void writeJson(HttpServletResponse response, int httpStatus, ErrorCode errorCode) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> body = Result.fail(errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
