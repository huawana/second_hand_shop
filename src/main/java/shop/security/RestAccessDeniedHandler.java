package shop.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import shop.common.ErrorCode;
import shop.common.Result;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 已认证但无权限（403）的统一处理。
 *
 * <p>与 401 的区别很重要，也是面试常问的点：
 * <b>401 = 我不知道你是谁</b>（未登录 / token 失效），<b>403 = 我知道你是谁，但你没权限</b>。
 * 混用会导致前端做错决策（收到 403 却去跳登录页，用户重新登录后依然 403，体验死循环）。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        String uri = request.getRequestURI();

        // CSRF 校验失败也是 AccessDeniedException 的子类（CsrfException）。
        // 必须单独识别：否则会被下面「权限不足 → 跳首页」的分支吞掉，
        // 表现成「提交表单后莫名其妙回到首页」，而真正的原因（缺 CSRF token）完全看不到。
        if (isCsrfFailure(accessDeniedException)) {
            log.warn("CSRF 校验失败（缺少或错误的 token） uri={} method={}", uri, request.getMethod());
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN, "请求校验失败，请刷新页面后重试");
            return;
        }

        // 权限不足是「安全事件」，用 WARN 记录，便于排查越权尝试
        log.warn("越权访问被拒绝 uri={} user={}", uri, CurrentUser.username());

        if (RequestTypeUtils.isApiRequest(request)) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN, null);
            return;
        }

        if (uri.startsWith("/admin/")) {
            response.sendRedirect("/admin/login");
        } else {
            response.sendRedirect("/shop/index");
        }
    }

    /** 判断异常链里是否包含 CSRF 校验失败 */
    private static boolean isCsrfFailure(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof CsrfException) {
                return true;
            }
        }
        return false;
    }

    private void writeJson(HttpServletResponse response, int httpStatus, ErrorCode errorCode, String messageOverride)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> body = (messageOverride == null)
                ? Result.fail(errorCode)
                : Result.fail(errorCode, messageOverride);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
