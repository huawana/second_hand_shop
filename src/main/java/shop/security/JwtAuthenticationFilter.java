package shop.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * JWT 认证过滤器 —— 整个无状态认证的入口。
 *
 * <p>职责：从请求中取出 token → 校验 → 把身份写入 {@code SecurityContext}。
 * 之后的授权判断（URL 规则、{@code @PreAuthorize}）都基于这个上下文，与 token 本身解耦。
 *
 * <p>取 token 的两种来源，缺一不可：
 * <ol>
 *   <li><b>Cookie</b>（浏览器场景）：服务端渲染的页面靠它自动携带；</li>
 *   <li><b>{@code Authorization: Bearer xxx}</b>（接口/移动端场景）：小程序、App、
 *       第三方对接不会用浏览器 Cookie，靠标准头传递。</li>
 * </ol>
 * 一个过滤器同时支持两种来源，就能让同一套认证服务于 Web 与 API 两类客户端。
 *
 * <p>为什么不抛异常：token 缺失/失效在这里是「常见且正常」的情况（用户没登录、token 过期），
 * 静默放行交给后面的授权过滤器处理即可 —— 由授权层统一决定是 401 还是跳登录页，
 * 避免「认证失败」和「未登录」两条路径产生不一致的响应。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 已经有认证信息（例如同一次请求里被其他机制设置了）就不重复认证
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = resolveToken(request);
            if (token != null) {
                LoginUser loginUser = jwtUtil.parse(token);
                if (loginUser != null) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    loginUser, null, loginUser.getAuthorities());
                    // details 里带上 IP / sessionId 等信息，便于审计与风控
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    /** 优先取标准请求头（API 客户端），其次取 Cookie（浏览器）。 */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            String cookieName = jwtUtil.getCookieName();
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
