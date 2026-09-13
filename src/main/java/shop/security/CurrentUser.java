package shop.security;

/**
 * 读取「当前登录者」的工具类。
 *
 * <p>认证信息统一存放在 {@code SecurityContextHolder}（ThreadLocal），
 * 业务代码通过本类取值，避免每个 Controller 都写一遍
 * {@code (LoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal()}。
 *
 * <p>注意：取不到时返回 {@code null} 而不是抛异常 —— 因为「未登录」在很多业务分支里
 * 是合法状态（例如首页要区分「匿名」与「已登录」两套展示逻辑）。
 */
public final class CurrentUser {

    private CurrentUser() {
        // 工具类禁止实例化
    }

    /** @return 当前登录者；匿名（未登录）时返回 {@code null} */
    public static LoginUser get() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        // 匿名请求的 principal 是字符串 "anonymousUser"，只认我们的 LoginUser
        return principal instanceof LoginUser loginUser ? loginUser : null;
    }

    /** @return 当前登录者的用户名；未登录返回 {@code null} */
    public static String username() {
        LoginUser user = get();
        return user == null ? null : user.getUsername();
    }

    /** @return 当前登录者的用户 id；未登录返回 {@code null} */
    public static Integer id() {
        LoginUser user = get();
        return user == null ? null : user.getId();
    }

    public static boolean isAuthenticated() {
        return get() != null;
    }

    public static boolean hasRole(String role) {
        LoginUser user = get();
        return user != null && role.equals(user.getRole());
    }
}
