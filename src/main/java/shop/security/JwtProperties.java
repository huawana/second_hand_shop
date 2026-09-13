package shop.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 相关配置（绑定 application.properties 里的 {@code jwt.*}）。
 *
 * <p>独立成类而不是散落的 {@code @Value}，好处：
 * <ul>
 *   <li>配置项集中、有类型、有默认值，IDE 能补全；</li>
 *   <li>可以直接作为 {@code @ConfigurationProperties} 被校验（见 {@link JwtUtil} 构造时的密钥长度检查）。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** 签名密钥。HS256 要求至少 256 bit（32 字节），生产必须用环境变量 JWT_SECRET 覆盖。 */
    private String secret;

    /** access token 有效期（分钟）。短寿命 + refresh token 是无状态认证的标准搭配。 */
    private long expireMinutes = 30;

    /** refresh token 有效期（天）。 */
    private long refreshExpireDays = 7;

    /** 承载 access token 的 Cookie 名。 */
    private String cookieName = "ACCESS_TOKEN";

    /** 承载 refresh token 的 Cookie 名。 */
    private String refreshCookieName = "REFRESH_TOKEN";

    /** Cookie 是否只在 HTTPS 下发送（本地开发为 false）。 */
    private boolean cookieSecure = false;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpireMinutes() {
        return expireMinutes;
    }

    public void setExpireMinutes(long expireMinutes) {
        this.expireMinutes = expireMinutes;
    }

    public long getRefreshExpireDays() {
        return refreshExpireDays;
    }

    public void setRefreshExpireDays(long refreshExpireDays) {
        this.refreshExpireDays = refreshExpireDays;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public String getRefreshCookieName() {
        return refreshCookieName;
    }

    public void setRefreshCookieName(String refreshCookieName) {
        this.refreshCookieName = refreshCookieName;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }
}
