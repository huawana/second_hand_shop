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

/**
 * JWT 签发与校验。
 *
 * <p>选型：<b>JWS + HS256 对称签名</b>。理由：单体应用，签发方与校验方是同一个服务，
 * 对称密钥最简单也最快；若将来 Phase 11 拆成多服务且需要「认证中心签发、业务服务只校验」，
 * 再换成 RS256（公私钥）—— 那时业务服务不需要持有能签发的私钥，安全性更高。
 *
 * <p>为什么 token 里只放 id / username / role，不放密码、手机号等敏感信息：
 * JWT 的 payload 只是 Base64Url 编码，<b>不是加密</b>，任何人都能解出内容。
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
     * 签发 token。
     *
     * <p>标准声明（subject / issuedAt / expiration）用 jjwt 的专用方法设置，
     * 自定义声明（uid / role）走 {@code claim()} —— 避免自定义字段名与标准字段冲突。
     */
    public String generate(LoginUser user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(properties.getExpireMinutes() * 60L);
        return Jwts.builder()
                .subject(user.getUsername())
                .claim(CLAIM_UID, user.getId())
                .claim(CLAIM_ROLE, user.getRole())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 校验并解析 token。
     *
     * @return 解析成功返回 {@link LoginUser}；签名不合法 / 已过期 / 格式错误统一返回 {@code null}
     */
    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)   // 校验签名：换过密钥的老 token 会在这里被拒
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String username = claims.getSubject();
            String role = claims.get(CLAIM_ROLE, String.class);
            Number uid = claims.get(CLAIM_UID, Number.class);
            if (username == null || role == null) {
                log.debug("token 缺少必要声明，拒绝");
                return null;
            }
            return new LoginUser(uid == null ? null : uid.intValue(), username, role);
        } catch (JwtException | IllegalArgumentException e) {
            // 过期、签名不符、被篡改都走这里。这是「预期内」的情况（用户带着旧 token 访问），
            // 用 DEBUG 记录即可，不需要告警 —— 前端拿到 401 自然会引导重新登录。
            log.debug("token 校验失败: {}", e.getMessage());
            return null;
        }
    }

    /** token 有效期（秒），用于 Cookie 的 maxAge。 */
    public long getExpireSeconds() {
        return properties.getExpireMinutes() * 60L;
    }

    public String getCookieName() {
        return properties.getCookieName();
    }

    public boolean isCookieSecure() {
        return properties.isCookieSecure();
    }
}
