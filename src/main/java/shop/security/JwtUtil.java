package shop.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与校验（access token）。
 *
 * <p>选型：<b>JWS + HS256 对称签名</b>。理由：单体应用，签发方与校验方是同一个服务，
 * 对称密钥最简单也最快；若将来 Phase 11 拆成多服务且需要「认证中心签发、业务服务只校验」，
 * 再换成 RS256（公私钥）—— 那时业务服务不需要持有能签发的私钥，安全性更高。
 *
 * <p>为什么 token 里只放 id / username / role，不放密码、手机号等敏感信息：
 * JWT 的 payload 只是 Base64Url 编码，<b>不是加密</b>，任何人都能解出内容。
 *
 * <p><b>本类只负责 access token。</b>refresh token 刻意<b>不用 JWT</b>，
 * 而是一串 Redis 里存着的不透明随机串 —— 原因见 {@link RefreshTokenService}。
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    private static final String CLAIM_UID = "uid";
    private static final String CLAIM_ROLE = "role";

    /** HS256 要求密钥长度 >= 256 bit */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtUtil(JwtProperties properties) {
        this.properties = properties;
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("未配置 jwt.secret，应用无法启动（避免用默认密钥签发可被伪造的 token）");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            // 提前失败：密钥过短会让 jjwt 在签发时抛 WeakKeyException，那时已经晚了
            throw new IllegalStateException(
                    "jwt.secret 长度不足：HS256 需要至少 " + MIN_SECRET_BYTES + " 字节，当前 " + keyBytes.length + " 字节");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 签发 access token。
     *
     * <p>标准声明（subject / id / issuedAt / expiration）用 jjwt 的专用方法设置；
     * 自定义声明（uid / role）走 {@code claim()}。
     *
     * <p>{@code id(jti)} 是为「可撤销」埋的钩子：JWT 本身无状态、无法撤回，
     * 但登出时把这个 jti 记进 Redis 黑名单（TTL = 令牌剩余寿命），
     * 过滤器一查就能让这一个令牌提前失效，同时黑名单条目到期自动消失、不会无限堆积。
     */
    public String generateAccess(LoginUser user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(properties.getExpireMinutes() * 60L);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getUsername())
                .claim(CLAIM_UID, user.getId())
                .claim(CLAIM_ROLE, user.getRole())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 校验并解析 access token。
     *
     * @return 成功返回 {@link AccessToken}（含 jti 与过期时间）；签名不合法 / 已过期 / 格式错误返回 {@code null}
     */
    public AccessToken parseAccess(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)   // 校验签名：换过密钥的老 token 会在这里被拒
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String username = claims.getSubject();
            String role = claims.get(CLAIM_ROLE, String.class);
            Number uid = claims.get(CLAIM_UID, Number.class);
            if (username == null || role == null || claims.getId() == null) {
                log.debug("token 缺少必要声明，拒绝");
                return null;
            }
            LoginUser user = new LoginUser(uid == null ? null : uid.intValue(), username, role);
            return new AccessToken(user, claims.getId(), claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            // 过期、签名不符、被篡改都走这里。这是「预期内」的情况（用户带着旧 token 访问），
            // 用 DEBUG 记录即可，不需要告警 —— 前端拿到 401 自然会引导重新登录。
            log.debug("token 校验失败: {}", e.getMessage());
            return null;
        }
    }

    /** access token 有效期（秒），用于 Cookie 的 maxAge 与黑名单 TTL 兜底。 */
    public long getAccessExpireSeconds() {
        return properties.getExpireMinutes() * 60L;
    }

    /** refresh token 有效期（秒）。 */
    public long getRefreshExpireSeconds() {
        return properties.getRefreshExpireDays() * 24 * 60 * 60L;
    }

    public String getCookieName() {
        return properties.getCookieName();
    }

    public String getRefreshCookieName() {
        return properties.getRefreshCookieName();
    }

    public boolean isCookieSecure() {
        return properties.isCookieSecure();
    }
}
