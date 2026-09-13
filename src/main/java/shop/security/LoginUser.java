package shop.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serializable;
import java.util.List;

/**
 * 认证主体（Principal）。
 *
 * <p>放进 {@code SecurityContext} 里的「当前登录者」。相比直接把 {@code User} 实体丢进去，
 * 这里只保留认证与授权真正需要的三要素：<b>身份 id、登录名、角色</b>。
 *
 * <p>为什么不用 {@code UserDetails} 实现：{@code UserDetails} 是为「用户名密码认证」设计的
 * （需要 password、accountNonExpired、enabled 等），而 JWT 场景下认证在签发 token 时已经完成，
 * 后续请求只需要一个承载身份与权限的载体。保持简单反而更清晰。
 */
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 角色常量：与 DB 中 {@code lxy_user.role} 的取值一致，不含 {@code ROLE_} 前缀 */
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    private final Integer id;
    private final String username;
    private final String role;

    public LoginUser(Integer id, String username, String role) {
        this.id = id;
        this.username = username;
        this.role = role;
    }

    public Integer getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }

    /**
     * 转换为 Spring Security 的权限集合。
     *
     * <p>注意 {@code hasRole("ADMIN")} 在底层比对的是 {@code "ROLE_ADMIN"} 这个字符串，
     * 所以这里必须补上前缀 —— 这是最常见的一个「配置看着没错但就是 403」的坑。
     */
    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String toString() {
        return "LoginUser{id=" + id + ", username='" + username + "', role='" + role + "'}";
    }
}
