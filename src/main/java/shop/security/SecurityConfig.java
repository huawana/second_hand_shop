package shop.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Spring Security 6 配置。
 *
 * <p>本项目的认证模型：<b>无状态 JWT</b>。
 * <ul>
 *   <li>登录成功后签发 JWT 写入 HttpOnly Cookie（见 {@link JwtCookieSupport}）；</li>
 *   <li>之后每个请求由 {@link JwtAuthenticationFilter} 校验 token 并重建 {@code SecurityContext}；</li>
 *   <li>服务端<b>不保存</b>登录状态：{@code SessionCreationPolicy.STATELESS}
 *       → 多实例部署时不需要 session 共享/粘性会话，这是拆微服务的前提。</li>
 * </ul>
 *
 * <p>关于 HttpSession 的说明（避免误解）：
 * {@code STATELESS} 指的是「Spring Security 不把认证状态存进 session」。
 * 本项目仍会用 session 承载<b>视图展示数据</b>（用户名、地区、学校）和一次性提示
 * （{@code saleError} / {@code changeError}）—— 这些是纯 UI 状态，不参与任何授权判断。
 * 换句话说：授权看 JWT，展示看 session，两者职责清晰分离。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // 开启 @PreAuthorize / @PostAuthorize 注解式鉴权
public class SecurityConfig {

    /**
     * 无需登录即可访问的路径。
     *
     * <p>只有三类：静态资源、登录注册入口、首页（首页本身要区分匿名/已登录两套展示逻辑，
     * 本身就是设计成可匿名访问的）。
     */
    private static final String[] PUBLIC_PATHS = {
            // ---- 基础设施 ----
            "/", "/error", "/favicon.ico",
            // ---- 静态资源 ----
            "/shop/assets/**", "/admin/assets/**",
            "/css/**", "/js/**", "/images/**", "/webjars/**",
            // ---- 前台入口 ----
            "/shop/login", "/shop/signup",
            "/shop/index", "/shop/index/**",
            "/shop/search", "/shop/checkSession",
            "/shop/productDetail/**",
            // ---- 【Phase 2】只读分类接口：商品目录属于公开信息，匿名可看 ----
            // 刻意逐条列出而不用 "/shop/api/**" 通配：将来新增的写接口不会因为
            // 一个宽通配符被顺手放行 —— 放行范围要「最小可用」，这是安全默认值。
            "/shop/api/categories", "/shop/api/categories/*",
            // ---- 后台入口 ----
            "/admin/login", "/admin/loginResult"
    };

    /**
     * 需要登录（任意角色）的前台写操作与私人页面。
     *
     * <p>这些端点在 Controller 里本来也有 {@code SessionCheck} 之类的校验，
     * 但那属于「业务代码自己兜底」。放到安全层再挡一次是有意为之：
     * <b>纵深防御</b> —— 将来有人新增接口忘了写校验，也不会直接暴露。
     */
    private static final String[] AUTHENTICATED_PATHS = {
            "/shop/cart", "/shop/addToCart", "/shop/deleteProduct",
            "/shop/buy", "/shop/buySuccess",
            "/shop/sale", "/shop/uploadProduct",
            "/shop/changeProduct", "/shop/changeProductInformation/**",
            "/shop/changeStatus", "/shop/deleteMyRelease",
            "/shop/person", "/shop/person*",
            "/shop/changePassword", "/shop/exit",
            "/shop/city", "/shop/school", "/shop/chooseCity", "/shop/chooseSchool"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) throws Exception {

        http
                // ------------------------------------------------------------------
                // CSRF：保持开启。
                // 有人会问「用了 JWT 还要 CSRF 吗？」—— 要。因为本项目 token 放在 Cookie 里，
                // 浏览器会自动携带，攻击者诱导用户点击就能冒用身份（这正是 CSRF 的定义）。
                // 只有「token 放 Authorization 头」的方案才天然免疫 CSRF。
                //
                // 【为什么用 CookieCsrfTokenRepository】
                // token 会同时下发到 XSRF-TOKEN Cookie，前端 JS 读取后放进 X-XSRF-TOKEN 请求头，
                // 这样 fetch / $.ajax 这两种「没有表单」的请求也能通过校验（见 static/shop/assets/JS/csrf.js）。
                //
                // 【为什么必须用非 Xor 的 CsrfTokenRequestAttributeHandler】
                // Spring Security 6 默认是 XorCsrfTokenRequestAttributeHandler，它为了防 BREACH
                // 会给 token 做一次随机掩码，导致「Cookie 里的原始值」与「服务端期望值」不一致 ——
                // 典型症状就是：表单提交能过、但前端把 Cookie 值放进请求头就 403。
                // 这里显式用不掩码的实现，让 Cookie / 表单隐藏域 / 请求头三处取值完全一致。
                // 代价是失去 BREACH 防护（对本项目以 JSON 为主的响应影响有限）。
                //
                // 【为什么显式指定 csrfRequestAttributeName="_csrf"】
                // RequestDataValueProcessor（Thymeleaf 的 th:action 靠它自动注入隐藏域）
                // 需要从 request 属性里取到 CsrfToken 对象。非 Xor 的实现默认不写这个属性，
                // 于是出现「th:action 正常渲染，但表单里没有 _csrf」这种很难定位的现象。
                // 显式指定属性名后，th:action 的自动注入与手写的
                // `th:name="${_csrf.parameterName}"` 两种写法都能拿到 token。
                // ------------------------------------------------------------------
                .csrf(csrf -> {
                    CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
                    requestHandler.setCsrfRequestAttributeName("_csrf");
                    csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(requestHandler)
                        // ------------------------------------------------------------------
                        // 【关键修复 · Phase 2.3 发现】禁止 Spring Security 在每次「认证」时轮换 CSRF token。
                        //
                        // CsrfConfigurer 默认会向会话认证策略里【追加】一个 CsrfAuthenticationStrategy
                        // （注意是追加，不是替换）—— 所以上面 sessionManagement 里设置了
                        // NullAuthenticatedSessionStrategy 也删不掉它，必须在这里单独置空。
                        //
                        // 为什么在本项目里必须关掉：
                        //   本项目是无状态的，每个请求都由 JwtAuthenticationFilter 重新认证；
                        //   而 SecurityContext 不落 session（SecurityContextRepository 里没有上下文），
                        //   于是 SessionManagementFilter 每次请求都判定为「发生了新认证」并触发该策略。
                        //   它的行为是：先 saveToken(null) 清掉 cookie —— 响应头里体现为
                        //     Set-Cookie: XSRF-TOKEN=; Max-Age=0; Expires=Thu, 01 Jan 1970 ...
                        //   再生成一个新 token。结果是：
                        //     1) 浏览器里的 CSRF cookie 被清掉/换掉，而当前页面渲染出的隐藏域还是旧值；
                        //     2) 用户在这个页面上做第二次写操作（再点一次加入购物车、删除、购买…）
                        //        必然 403「请求校验失败，请刷新页面后重试」—— 必须刷新页面才能继续。
                        //   这个轮换在无状态场景下也毫无意义：每次请求都轮换，等于不存在有效基线。
                        //
                        // 这个坑很难查：单次操作永远是成功的，只有「连续两次写操作」才复现，
                        // 而且错误信息会把责任推给用户（"请刷新页面"）。这里在验证脚本里
                        // 明确加了「连续两次 addToCart 都必须 200」的断言来锁住它。
                        // ------------------------------------------------------------------
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy());
                })

                // ------------------------------------------------------------------
                // 会话策略：不创建也不使用 HttpSession 存认证信息
                //
                // 【踩坑记录 —— 这个坑很隐蔽，值得单独说明】
                // 只配 sessionCreationPolicy(STATELESS) 是不够的。Spring Security 默认还会把
                // SessionManagementFilter 挂进过滤器链，并在「检测到一次新认证」时执行
                // 会话固定攻击防护（ChangeSessionIdAuthenticationStrategy），顺带
                // 重置 CSRF token（CsrfAuthenticationStrategy）。
                // 问题是：无状态模式下每次请求的认证都是「新的」（SecurityContext 不落 session），
                // 于是这两个策略每个请求都触发一次 —— 症状是
                //   1) 每次响应都下发新的 JSESSIONID（session id 疯狂轮换，
                //      任何缓存了 Cookie 的客户端（含浏览器预取、压测工具）下一次请求就丢会话）；
                //   2) 每次响应都替换 CSRF token，页面里已渲染的隐藏域随即失效，
                //      用户的表单提交会偶发 403，且极难复现。
                // 解法：显式会话认证策略置为空实现 —— 无状态场景下本来就不需要迁移会话。
                // ------------------------------------------------------------------
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                )

                // ------------------------------------------------------------------
                // 授权规则（顺序敏感：先匹配先生效）
                // ------------------------------------------------------------------
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // 后台管理：必须是 ADMIN 角色。
                        // 注意入口路径 /admin/login 已在上面放行，否则管理员无法登录（死循环）。
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers(AUTHENTICATED_PATHS).authenticated()
                        .anyRequest().permitAll()
                )

                // ------------------------------------------------------------------
                // 异常分流：401/403 按「接口 vs 页面」返回不同形式
                // ------------------------------------------------------------------
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )

                // 关闭 Spring Security 自带的表单登录 / HTTP Basic / 登出页：
                // 本项目的登录页与登出逻辑是自己实现的（要签发 JWT 并写 Cookie），
                // 不关掉的话它们会抢占默认入口，产生「跳到一个空白默认登录页」的怪现象。
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                // 把 JWT 过滤器插在用户名密码过滤器之前：
                // 保证进入授权判断时 SecurityContext 已经装配完毕。
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密码编码器：BCrypt。
     *
     * <p>相比原来的「无盐 MD5」：
     * <ul>
     *   <li><b>自带随机盐</b> —— 同一密码每次编码结果都不同，彩虹表彻底失效；</li>
     *   <li><b>慢哈希</b> —— 通过 strength（默认 10，即 2^10 轮）故意拉长计算时间，
     *       GPU 每秒能算百亿次 MD5，却只能算几百次 BCrypt，暴力破解成本被抬高若干个数量级；</li>
     *   <li>盐值直接编码在结果串里（形如 {@code $2a$10$...}），不需要额外的 salt 字段。</li>
     * </ul>
     *
     * <p>strength 的取舍：每 +1 计算耗时翻倍。10 在「安全性」与「登录延迟」之间是通用平衡点；
     * 若部署机器 CPU 较强且安全要求高，可提到 12。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
