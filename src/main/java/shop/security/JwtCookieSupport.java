package shop.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;

/**
 * JWT 在 Cookie 中的读写。
 *
 * <p>为什么用 Cookie 而不是 {@code Authorization} 头（本项目特有的约束）：
 * 页面跳转（{@code <a href>}）和表单提交 {@code <form action>} 是浏览器发起的，
 * <b>无法手动附加请求头</b>，只有 Cookie 会被自动携带。若强行用 localStorage + 请求头，
 * 意味着要把所有服务端渲染页面改造成前端路由 —— 收益低、风险高。
 *
 * <p>三个安全属性（缺一不可）：
 * <ul>
 *   <li>{@code HttpOnly} —— JS 读不到，XSS 注入脚本也偷不走 token；</li>
 *   <li>{@code Secure}   —— 仅在 HTTPS 下发送，防中间人窃听（本地开发关）；</li>
 *   <li>{@code SameSite=Lax} —— 跨站请求不携带该 Cookie，是 CSRF 的第一道防线
 *       （Lax 而非 Strict：Strict 会导致「从外部链接点进来时显示未登录」，体验差）。</li>
 * </ul>
 */
@Component
public class JwtCookieSupport {

    private final JwtUtil jwtUtil;

    public JwtCookieSupport(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /** 登录成功后写入 token（HttpOnly Cookie）。 */
    public void writeToken(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(jwtUtil.getCookieName(), token)
                .httpOnly(true)
                .secure(jwtUtil.isCookieSecure())
                .path("/")
                .maxAge(jwtUtil.getExpireSeconds())
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 退出登录：把 Cookie 置为 maxAge=0 让浏览器立即丢弃。
     *
     * <p>注意 Cookie 的删除必须「同名 + 同 path + 同 domain」才会生效，
     * 只写一个同名的空 Cookie 而 path 不同是删不掉的 —— 常见坑。
     */
    public void clearToken(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(jwtUtil.getCookieName(), "")
                .httpOnly(true)
                .secure(jwtUtil.isCookieSecure())
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
