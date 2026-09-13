package shop.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtUtil} 单元测试。
 *
 * <p>重点覆盖「可撤销」相关的两个新增声明（jti、expiration），
 * 以及密钥校验、篡改拒绝这些安全边界 —— 这些一旦写错，
 * 表现是「看起来能登录，但黑名单/续期悄悄失效」，很难在功能测试里发现。
 */
class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-key-at-least-32-bytes-long!!";

    private static JwtProperties props() {
        JwtProperties p = new JwtProperties();
        p.setSecret(SECRET);
        p.setExpireMinutes(30);
        p.setRefreshExpireDays(7);
        p.setCookieName("ACCESS_TOKEN");
        p.setRefreshCookieName("REFRESH_TOKEN");
        return p;
    }

    private static JwtUtil util() {
        return new JwtUtil(props());
    }

    @Test
    @DisplayName("签发的 access token 能被解析回原身份，且带 jti 与过期时间")
    void generateAndParse_roundTrip() {
        JwtUtil jwt = util();
        LoginUser user = new LoginUser(4, "lisi", LoginUser.ROLE_USER);

        AccessToken parsed = jwt.parseAccess(jwt.generateAccess(user));

        assertThat(parsed).isNotNull();
        assertThat(parsed.user().getUsername()).isEqualTo("lisi");
        assertThat(parsed.user().getId()).isEqualTo(4);
        assertThat(parsed.user().getRole()).isEqualTo(LoginUser.ROLE_USER);
        // jti 是黑名单的 key，绝不能为空
        assertThat(parsed.jti()).isNotBlank();
        // 过期时间必须真的在将来（黑名单 TTL 靠它计算，错了会导致条目永不清理或立刻失效）
        assertThat(parsed.expiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("每次签发的 jti 都不同（否则登出一个令牌会把其他会话一起拉黑）")
    void jti_isUniquePerToken() {
        JwtUtil jwt = util();
        LoginUser user = new LoginUser(4, "lisi", LoginUser.ROLE_USER);

        String jti1 = jwt.parseAccess(jwt.generateAccess(user)).jti();
        String jti2 = jwt.parseAccess(jwt.generateAccess(user)).jti();

        assertThat(jti1).isNotEqualTo(jti2);
    }

    @Test
    @DisplayName("管理员角色能正确往返")
    void adminRole_roundTrip() {
        JwtUtil jwt = util();
        AccessToken parsed = jwt.parseAccess(
                jwt.generateAccess(new LoginUser(1, "admin", LoginUser.ROLE_ADMIN)));
        assertThat(parsed.user().getRole()).isEqualTo(LoginUser.ROLE_ADMIN);
        assertThat(parsed.user().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("被篡改的 token 解析失败（返回 null 而不是抛异常）")
    void tamperedToken_returnsNull() {
        JwtUtil jwt = util();
        String token = jwt.generateAccess(new LoginUser(4, "lisi", LoginUser.ROLE_USER));
        // 改动 payload 段的最后一个字符，签名随即不匹配
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("A") ? "B" : "A");

        assertThat(jwt.parseAccess(tampered)).isNull();
    }

    @Test
    @DisplayName("换过密钥后老 token 失效（密钥轮换的正确行为）")
    void tokenFromAnotherKey_isRejected() {
        String token = util().generateAccess(new LoginUser(4, "lisi", LoginUser.ROLE_USER));

        JwtProperties other = props();
        other.setSecret("another-different-secret-key-32-bytes-min!!");
        JwtUtil otherJwt = new JwtUtil(other);

        assertThat(otherJwt.parseAccess(token)).isNull();
    }

    @Test
    @DisplayName("已过期的 token 解析失败")
    void expiredToken_returnsNull() {
        JwtProperties expired = props();
        expired.setExpireMinutes(-1);   // 签发即为过去时间
        JwtUtil jwt = new JwtUtil(expired);

        String token = jwt.generateAccess(new LoginUser(4, "lisi", LoginUser.ROLE_USER));

        assertThat(jwt.parseAccess(token)).isNull();
    }

    @Test
    @DisplayName("垃圾字符串、null、空串都安全返回 null")
    void malformedInput_returnsNull() {
        JwtUtil jwt = util();
        assertThat(jwt.parseAccess("not-a-jwt")).isNull();
        assertThat(jwt.parseAccess("")).isNull();
        assertThat(jwt.parseAccess(null)).isNull();
    }

    @Test
    @DisplayName("密钥缺失或过短必须启动即失败，而不是签出可被伪造的弱 token")
    void weakSecret_failsFast() {
        JwtProperties blank = props();
        blank.setSecret("   ");
        assertThatThrownBy(() -> new JwtUtil(blank))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret");

        JwtProperties tooShort = props();
        tooShort.setSecret("short");
        assertThatThrownBy(() -> new JwtUtil(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少");
    }

    @Test
    @DisplayName("有效期换算：access 按分钟、refresh 按天")
    void expireSeconds_math() {
        JwtProperties p = props();
        p.setExpireMinutes(30);
        p.setRefreshExpireDays(7);
        JwtUtil jwt = new JwtUtil(p);

        assertThat(jwt.getAccessExpireSeconds()).isEqualTo(30 * 60L);
        assertThat(jwt.getRefreshExpireSeconds()).isEqualTo(7 * 24 * 60 * 60L);
    }

    @Test
    @DisplayName("Properties 默认值符合设计：access 30 分钟、refresh 7 天")
    void defaultValues() {
        JwtProperties p = new JwtProperties();
        assertThat(p.getExpireMinutes()).isEqualTo(30);
        assertThat(p.getRefreshExpireDays()).isEqualTo(7);
        assertThat(p.getCookieName()).isEqualTo("ACCESS_TOKEN");
        assertThat(p.getRefreshCookieName()).isEqualTo("REFRESH_TOKEN");
    }
}
