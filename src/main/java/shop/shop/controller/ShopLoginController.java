package shop.shop.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import shop.admin.Bean.User;
import shop.admin.mapper.CartMapper;
import shop.admin.mapper.UserMapper;
import shop.security.AccessToken;
import shop.security.JwtCookieSupport;
import shop.security.JwtUtil;
import shop.security.LoginUser;
import shop.security.PasswordService;
import shop.security.RedisTokenStore;
import shop.security.RefreshTokenService;
import shop.shop.tools.StringContainsMultipleTypes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Slf4j
@Controller
public class ShopLoginController {
    @Autowired
    UserMapper userMapper;

    @Autowired
    CartMapper cartMapper;

    /** 【Phase 1】密码校验 + 存量 MD5 透明升级，替换原来的 MD5passEncryption 直接比对 */
    @Autowired
    PasswordService passwordService;

    /** 【Phase 1】签发 JWT */
    @Autowired
    JwtUtil jwtUtil;

    /** 【Phase 1】把 token 写入 HttpOnly Cookie */
    @Autowired
    JwtCookieSupport jwtCookieSupport;

    /** 【Phase 1 收尾】refresh token 的签发/轮换/吊销（Redis 支撑，可即时撤销） */
    @Autowired
    RefreshTokenService refreshTokenService;

    /** 【Phase 1 收尾】access token 黑名单（让无状态 JWT 也能被提前撤销） */
    @Autowired
    RedisTokenStore tokenStore;

    @GetMapping("/shop/login")
    public String shopLogin(){
        return "shop/login";
    }

    /**
     * 登录。
     *
     * <p>【Phase 1 改造】认证方式从「MD5 摘要字符串比对」升级为：
     * <ol>
     *   <li>{@link PasswordService#matches} 校验 —— 兼容存量 MD5，新密码走 BCrypt；</li>
     *   <li>校验通过后若发现库里还是 MD5，<b>顺手升级为 BCrypt</b>（用户无感知）；</li>
     *   <li>签发 JWT 写入 HttpOnly Cookie，后续请求由过滤器识别身份。</li>
     * </ol>
     * 注意 session 里的 {@code shopusername} 等属性仍然保留 —— 它们只用于页面渲染展示，
     * 不再承担任何「是否已登录」的判断（那由 JWT + Spring Security 负责）。
     */
    @PostMapping("/shop/login")
    public String validateUser(String username, String password, HttpServletRequest request,
                               HttpServletResponse response, Model m){
        User user = userMapper.getUserByUsername(username);
        if(user == null){
            // 安全考虑：不区分「用户名不存在」与「密码错误」对外提示，
            // 否则等于给攻击者一个枚举有效用户名的接口（用户名字典攻击）。
            m.addAttribute("loginMistake","用户名或密码不正确");
            return "shop/login";
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            m.addAttribute("loginMistake","该账号已被禁用，请联系管理员");
            return "shop/login";
        }
        if (!passwordService.matches(password, user.getPassword())) {
            m.addAttribute("loginMistake","用户名或密码不正确");
            return "shop/login";
        }

        // ---- 存量密码透明升级：MD5 → BCrypt ----
        if (passwordService.isLegacyMd5(user.getPassword())) {
            userMapper.changePassword(username, passwordService.encode(password));
            passwordService.logUpgrade(username);
        }

        // ---- 签发 access token + refresh token，写入 HttpOnly Cookie ----
        // 短寿命 access（默认 30 分钟，无状态校验，每个请求不查存储）
        // + 长寿命 refresh（默认 7 天，Redis 可即时吊销）
        // access 过期后由 JwtAuthenticationFilter 用 refresh 透明续期，用户无感知。
        String role = user.getRole() == null || user.getRole().isBlank()
                ? LoginUser.ROLE_USER : user.getRole();
        LoginUser loginUser = new LoginUser(user.getId(), username, role);
        jwtCookieSupport.writeAccess(response, jwtUtil.generateAccess(loginUser));
        jwtCookieSupport.writeRefresh(response, refreshTokenService.issue(loginUser).token());

        HttpSession session = request.getSession();
        session.setAttribute("shopusername",username);
        session.setAttribute("province",user.getProvince());
        session.setAttribute("city",user.getCity());
        session.setAttribute("area",user.getArea());
        session.setAttribute("school",user.getSchool());
        m.addAttribute("shopusername",session.getAttribute("shopusername"));
        log.info("用户[{}]登录成功 role={}", username, role);
        return "redirect:/shop/index";
    }

    @PostMapping("/shop/signup")
    public String signup(String username, String password, String confirmpassword,HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        if(username.length()>8){
            m.addAttribute("signUpMistake","用户名过长");
            return "shop/signup";
        }else if(!StringContainsMultipleTypes.stringJudgeIfContainTwoType(password)){
            m.addAttribute("signUpMistake","密码格式不符");
            return "shop/signup";
        }else if(!Objects.equals(confirmpassword, password)){
            m.addAttribute("signUpMistake","两次密码不一致");
            return "shop/signup";
        }else if(password.length()<8||password.length()>16){
            m.addAttribute("signUpMistake","密码格式不符");
            return "shop/signup";
        }else{
            // 【Phase 1】新用户直接用 BCrypt 存储，不再是 MD5
            String encodedPassword = passwordService.encode(password);
            userMapper.addUser(username,encodedPassword,null,null);
            int id = userMapper.getIdByUserName(username);
            cartMapper.addUserCartById(id);
            session.setAttribute("shopusername",username);
            m.addAttribute("shopusername",session.getAttribute("shopusername"));
            return "redirect:/shop/index";
        }
    }



    @GetMapping("/shop/signup")
    public String shopSignUP(){
        return "shop/signup";
    }

    /**
     * 退出登录。
     *
     * <p>【Phase 1 收尾】从「只清 Cookie」升级为真正的<b>吊销</b>，三步缺一不可：
     * <ol>
     *   <li>删除 Redis 里的 refresh token —— 否则攻击者可以拿它把会话续回来；</li>
     *   <li>把当前 access token 的 jti 记进黑名单（TTL = 令牌剩余寿命）——
     *       否则这个「无状态的 JWT」在自然过期前，谁拿到它谁就能用；</li>
     *   <li>清掉两个 Cookie —— 否则浏览器下次请求还会带着它们。</li>
     * </ol>
     * 只做第 3 步是最常见的错误实现：看起来登出了，其实凭证还在有效期内。
     */
    @GetMapping("/shop/exit")
    public String shopExit(HttpServletRequest request, HttpServletResponse response){
        // 1) 作废 refresh token，断掉「自动续期」这条路
        refreshTokenService.revoke(jwtCookieSupport.readRefreshToken(request));
        // 2) access token 拉黑：TTL 只留到它自然过期那一刻，黑名单因此不会无限增长
        String accessToken = jwtCookieSupport.readAccessToken(request);
        if (accessToken != null) {
            AccessToken parsed = jwtUtil.parseAccess(accessToken);
            if (parsed != null) {
                tokenStore.blacklistAccess(parsed.jti(), Duration.between(Instant.now(), parsed.expiresAt()));
            }
        }
        // 3) 清掉两个 Cookie
        jwtCookieSupport.clearAccess(response);
        jwtCookieSupport.clearRefresh(response);

        HttpSession session = request.getSession();
        String username = (String) session.getAttribute("shopusername");
        session.removeAttribute("shopusername");
        session.removeAttribute("province");
        session.removeAttribute("city");
        session.removeAttribute("area");
        session.removeAttribute("school");
        log.info("用户[{}]退出登录", username);
        return "redirect:/shop/login";
    }

    @GetMapping("/shop/changePassword")
    public String changePassword(HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        String changeError = (String) session.getAttribute("changeError");
        m.addAttribute("changeError",changeError);
        session.removeAttribute("changeError");
        return "shop/changePassword";
    }

    @PostMapping("/shop/changePassword")
    public String changePass(String username, String oldpassword,String newpassword, String confirmpassword,HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        User user = userMapper.getUserByUsername(username);
        if(user == null){
            session.setAttribute("changeError","用户名或密码错误");
            return "redirect:/shop/changePassword";
        }
        // 【Phase 1】兼容 MD5 / BCrypt 两种旧格式校验
        if(passwordService.matches(oldpassword, user.getPassword())){
             if(newpassword.length()<8||newpassword.length()>16) {
                 session.setAttribute("changeError", "密码格式不符");
                 return "redirect:/shop/changePassword";
             }else if(!StringContainsMultipleTypes.stringJudgeIfContainTwoType(newpassword)) {
                 session.setAttribute("changeError", "密码格式不符");
                 return "redirect:/shop/changePassword";
             }else if(!newpassword.equals(confirmpassword)){
                 session.setAttribute("changeError","两次输入的密码不一致");
                 return "redirect:/shop/changePassword";
             }else{
                 // 【Phase 1】改密码后统一存 BCrypt
                 userMapper.changePassword(username, passwordService.encode(newpassword));
                 // 【Phase 1 收尾】改密码后撤销该用户全部 refresh token。
                 // 这是「密码疑似泄露 → 改密码」场景的关键一步：不撤销的话，
                 // 攻击者手里的 refresh token 仍能把会话一直续下去，改密码就形同虚设。
                 int revoked = refreshTokenService.revokeAllForUser(user.getId());
                 log.info("用户[{}]修改密码，已撤销 {} 个 refresh token", username, revoked);
                 return "redirect:/shop/login";

             }


        }else{
            session.setAttribute("changeError","用户名或密码错误");
            return "redirect:/shop/changePassword";
        }
    }
}
