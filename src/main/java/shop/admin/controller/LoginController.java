package shop.admin.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import shop.admin.Bean.Admin;
import shop.admin.mapper.AdminMapper;
import shop.security.AccessToken;
import shop.security.JwtCookieSupport;
import shop.security.JwtUtil;
import shop.security.LoginUser;
import shop.security.PasswordService;
import shop.security.RedisTokenStore;
import shop.security.RefreshTokenService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Slf4j
@Controller
public class LoginController {

    @Autowired
    AdminMapper adminMapper;

    @Autowired
    PasswordService passwordService;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    JwtCookieSupport jwtCookieSupport;

    /** 【Phase 1 收尾】refresh token 的签发/轮换/吊销 */
    @Autowired
    RefreshTokenService refreshTokenService;

    /** 【Phase 1 收尾】access token 黑名单 */
    @Autowired
    RedisTokenStore tokenStore;

    @GetMapping("/admin/login")
    public String login(HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        String error = (String)session.getAttribute("loginError");
        m.addAttribute("loginError",error);
        session.removeAttribute("loginError");
        return "admin/login";
    }

    /**
     * 管理员登录。
     *
     * <p>【Phase 1 改造】
     * <ol>
     *   <li>原实现是 {@code getAdmins()} 查全表再在内存里遍历比对 ——
     *       用户量一大就是典型的全表扫描 + 明文摘要驻留内存。改为按用户名精确查询（走唯一索引）。</li>
     *   <li>密码校验走 {@link PasswordService}，兼容存量 MD5 并透明升级为 BCrypt。</li>
     *   <li>签发的 JWT 角色为 {@code ADMIN} → 才能通过 {@code /admin/**} 的 {@code hasRole("ADMIN")} 校验。</li>
     * </ol>
     */
    @PostMapping("/admin/loginResult")
    public String confirmPass(String adminuser, String adminpass, HttpServletRequest request,
                             HttpServletResponse response, Model m){
        HttpSession session = request.getSession();
        Admin admin = adminMapper.getAdmin(adminuser);
        if (admin == null || !passwordService.matches(adminpass, admin.getAdminpass())) {
            // 统一提示，避免暴露「管理员账号是否存在」
            session.setAttribute("loginError","用户名或密码错误");
            return "redirect:/admin/login";
        }

        // 存量密码透明升级：MD5 → BCrypt
        if (passwordService.isLegacyMd5(admin.getAdminpass())) {
            adminMapper.UpdateAdminPassword(adminuser, passwordService.encode(adminpass));
            passwordService.logUpgrade("admin:" + adminuser);
        }

        // 签发 ADMIN 角色的 access + refresh token（与前台同一套机制，只是角色不同）
        LoginUser loginUser = new LoginUser(admin.getId(), adminuser, LoginUser.ROLE_ADMIN);
        jwtCookieSupport.writeAccess(response, jwtUtil.generateAccess(loginUser));
        jwtCookieSupport.writeRefresh(response, refreshTokenService.issue(loginUser).token());

        session.setAttribute("adminuser", adminuser);
        adminMapper.UpdateAdminLoginTime(adminuser, new Date());
        m.addAttribute("admin",admin.getAdminuser());
        log.info("管理员[{}]登录成功", adminuser);
        return "redirect:/admin/admin";
    }

    /**
     * 管理员退出登录。
     *
     * <p>【Phase 1 收尾】与前台一致：refresh token 作废 + access token 拉黑 + 清 Cookie，
     * 三者缺一不可（只清 Cookie 是最常见的错误实现 —— 看起来登出了，凭证其实还有效）。
     */
    @GetMapping("/admin/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response){
        refreshTokenService.revoke(jwtCookieSupport.readRefreshToken(request));
        String accessToken = jwtCookieSupport.readAccessToken(request);
        if (accessToken != null) {
            AccessToken parsed = jwtUtil.parseAccess(accessToken);
            if (parsed != null) {
                tokenStore.blacklistAccess(parsed.jti(), Duration.between(Instant.now(), parsed.expiresAt()));
            }
        }
        jwtCookieSupport.clearAccess(response);
        jwtCookieSupport.clearRefresh(response);

        HttpSession session = request.getSession();
        String adminuser = (String) session.getAttribute("adminuser");
        session.removeAttribute("adminuser");
        log.info("管理员[{}]退出登录", adminuser);
        return "redirect:/admin/login";
    }
}
