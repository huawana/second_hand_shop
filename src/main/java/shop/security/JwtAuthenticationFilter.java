package shop.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器 —— 整个无状态认证的入口。
 *
 * <p>职责链（按顺序）：
 * <ol>
 *   <li>从 {@code Authorization: Bearer} 头或 Cookie 取出 access token；</li>
 *   <li>校验签名与有效期；</li>
 *   <li><b>查黑名单</b> —— 登出 / 强制下线的令牌在这里被拦下（JWT 本身撤不回，
 *       靠 jti 黑名单补齐这个能力）；</li>
 *   <li>access token 不可用时，尝试用 refresh token <b>透明续期</b>：
 *       换发新的 access + refresh 并写回 Cookie。用户完全无感知，
 *       不需要前端做任何事，也不需要跳登录页。</li>
 * </ol>
 *
 * <p>为什么不抛异常：token 缺失/失效在这里是「常见且正常」的情况（用户没登录、令牌过期），
 * 静默放行交给后面的授权过滤器处理即可 —— 由授权层统一决定是 401 还是跳登录页，
 * 避免「认证失败」和「未登录」两条路径产生不一致的响应。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 静态资源不参与透明续期：它们量大、且续期对它们毫无意义（不写 Cookie 更省） */
    private static final List<String> SKIP_REFRESH_PREFIXES =
            List.of("/shop/assets/", "/admin/assets/", "/css/", "/js/", "/images/", "/webjars/", "/favicon.ico");

    private final JwtUtil jwtUtil;
    private final JwtCookieSupport cookieSupport;
    private final RedisTokenStore tokenStore;
    private final RefreshTokenService refreshTokenService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   JwtCookieSupport cookieSupport,
                                   RedisTokenStore tokenStore,
                                   RefreshTokenService refreshTokenService) {
        this.jwtUtil = jwtUtil;
        this.cookieSupport = cookieSupport;
        this.tokenStore = tokenStore;
        this.refreshTokenService = refreshTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 已经有认证信息（例如同一次请求里被其他机制设置了）就不重复认证
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String accessToken = resolveAccessToken(request);
            AccessToken parsed = (accessToken == null) ? null : jwtUtil.parseAccess(accessToken);

            if (parsed != null && !tokenStore.isAccessBlacklisted(parsed.jti())) {
                authenticate(parsed.user(), request);
            } else {
                // access token 缺失 / 过期 / 已被拉黑 → 尝试用 refresh token 续期
                tryRefresh(request, response);
            }
        }

        filterChain.doFilter(request, response);
    }

    /** 用 refresh token 换新令牌并写回 Cookie；失败则静默保持匿名。 */
    private void tryRefresh(HttpServletRequest request, HttpServletResponse response) {
        String uri = request.getRequestURI();
        for (String prefix : SKIP_REFRESH_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return;
            }
        }
        String refreshToken = cookieSupport.readRefreshToken(request);
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokenService.rotate(refreshToken).ifPresent(issued -> {
            cookieSupport.writeAccess(response, jwtUtil.generateAccess(issued.user()));
            cookieSupport.writeRefresh(response, issued.token());
            authenticate(issued.user(), request);
        });
    }

    private void authenticate(LoginUser loginUser, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
        // details 里带上 IP / sessionId 等信息，便于审计与风控
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** 优先取标准请求头（API 客户端），其次取 Cookie（浏览器）。 */
    private String resolveAccessToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        return cookieSupport.readAccessToken(request);
    }
}
