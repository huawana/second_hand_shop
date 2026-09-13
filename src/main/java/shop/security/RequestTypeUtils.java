package shop.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * 判断一个请求是「接口请求」还是「页面请求」。
 *
 * <p>这个区分贯穿整个安全层：401/403 时接口返回 JSON、页面跳登录页；
 * 以及 CSRF 之外的其他异常处理也依赖它。
 *
 * <p>判定依据（三者取其一）：
 * <ol>
 *   <li>{@code Accept} 头包含 {@code application/json}；</li>
 *   <li>{@code X-Requested-With: XMLHttpRequest}（jQuery 默认加，fetch 需手动加）；</li>
 *   <li>路径命中已知的纯接口端点集合。</li>
 * </ol>
 * 第 3 条是兜底：本项目前端有些 {@code fetch} 调用没有设置 Accept 头，
 * 只靠前两条会误判成页面请求，于是返回 302 —— 而 fetch 会自动跟随重定向，
 * 最终拿到的是登录页 HTML，前端却按 JSON 解析，报出难以定位的语法错误。
 * 显式列白名单虽然「不够优雅」，但行为可预测，排查成本最低。
 */
public final class RequestTypeUtils {

    private RequestTypeUtils() {
    }

    /** 项目里返回 JSON（而非视图名）的端点，新增接口时需同步维护 */
    private static final Set<String> API_PATHS = Set.of(
            "/shop/checkSession",
            "/shop/addToCart",
            "/shop/buy",
            "/shop/buySuccess",
            "/shop/changeStatus",
            "/shop/deleteMyRelease",
            // 【Phase 2】分类接口返回 JSON，未登录时也应得到 401 JSON 而不是 302 跳登录页
            "/shop/api/categories",
            "/shop/api/categories/page"
    );

    public static boolean isApiRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("application/json")) {
            return true;
        }
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            return true;
        }
        return API_PATHS.contains(request.getRequestURI());
    }
}
