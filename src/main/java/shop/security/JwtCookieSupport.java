package shop.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 认证 Cookie 的读写（access token + refresh token 两个）。
 *
 * <p>为什么用 Cookie 而不是 {@code Authorization} 头（本项目特有的约束）：
 * 页面跳转（{@code <a href>}）和表单提交 {@code <form action>} 是浏览器发起的，
 * <b>无法手动附加请求头</b>，只有 Cookie 会被自动携带。若强行用 localStorage + 请求头，
 * 意味着要把所有服务端渲染页面改造成前端路由 —— 收益低、风险高。
 *
 * <p>两个 Cookie 都用同一组安全属性（缺一不可）：
 * <ul>
 *   <li>{@code HttpOnly} —— JS 读不到，XSS 注入脚本也偷不走 token；</li>
 *   <li>{@code Secure}   —— 仅在 HTTPS 下发送，防中间人窃听（本地开发关）；</li>
 *   <li>{@code SameSite=Lax} —— 跨站请求不携带该 Cookie，是 CSRF 的第一道防线
 *       （Lax 而非 Strict：Strict 会导致「从外部链接点进来时显示未登录」，体验差）。</li>
 * </ul>
 *
 * <p>refresh token 的 Cookie 刻意<b>没有</b>单独收窄 path（例如限制到 /shop/refresh）：
 * 本项目是在过滤器里透明刷新的，可能的刷新点覆盖所有页面，收窄 path 会让大部分请求
 * 拿不到 refresh token，反而把「透明刷新」这个能力废掉。
 */
@Component
public class JwtCookieSupport {

    private final JwtUtil jwtUtil;

    public JwtCookieSupport(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    // ------------------------------------------------------------------ 写

    /** 登录 / 刷新后写入 access token。 */
    public void writeAccess(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                build(jwtUtil.getCookieName(), token, jwtUtil.getAccessExpireSeconds()).toString());
    }

    /** 登录 / 刷新后写入 refresh token。 */
    public void writeRefresh(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                build(jwtUtil.getRefreshCookieName(), token, jwtUtil.getRefreshExpireSeconds()).toString());
    }

    /** 退出登录：清 access token。 */
    public void clearAccess(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(jwtUtil.getCookieName(), "", 0).toString());
    }

    /** 退出登录：清 refresh token。 */
    public void clearRefresh(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(jwtUtil.getRefreshCookieName(), "", 0).toString());
    }

    // ------------------------------------------------------------------ 读

    public String readAccessToken(HttpServletRequest request) {
        return read(request, jwtUtil.getCookieName());
    }

    public String readRefreshToken(HttpServletRequest request) {
        return read(request, jwtUtil.getRefreshCookieName());
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 删除 Cookie 必须「同名 + 同 path + 同 domain」，只写一个同名的空 Cookie
     * 而 path 不同是删不掉的 —— 常见坑。这里复用同一个构造方法就是为了保证一致。
     */
    private ResponseCookie build(String name, String value, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(jwtUtil.isCookieSecure())
                .path("/")
                .maxAge(maxAgeSeconds)
                .sameSite("Lax")
                .build();
    }

    private static String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
