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
import shop.security.JwtCookieSupport;
import shop.security.JwtUtil;
import shop.security.LoginUser;
import shop.security.PasswordService;
import shop.shop.tools.StringContainsMultipleTypes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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

        // ---- 签发 JWT 并写入 HttpOnly Cookie ----
        String role = user.getRole() == null || user.getRole().isBlank()
                ? LoginUser.ROLE_USER : user.getRole();
        String token = jwtUtil.generate(new LoginUser(user.getId(), username, role));
        jwtCookieSupport.writeToken(response, token);

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
     * <p>【Phase 1 改造】必须清除 HttpOnly Cookie，否则 JWT 仍然有效
     * —— 只删 session 属性是删不掉认证凭证的（这正是「退出登录没生效」的典型原因）。
     *
     * <p>局限：无状态的 JWT 在过期前本身仍然合法，清除 Cookie 只是让浏览器不再携带它。
     * 若用户在其他地方拷贝过 token，它依然可用 —— 要彻底解决需引入
     * <b>token 黑名单（Redis）+ 短期 access token + 长期 refresh token</b>，
     * 这部分计划在 Phase 1 的后续迭代中补上（Redis 已在运行，可直接接入）。
     */
    @GetMapping("/shop/exit")
    public String shopExit(HttpServletRequest request, HttpServletResponse response){
        jwtCookieSupport.clearToken(response);
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
                 log.info("用户[{}]修改密码", username);
                 return "redirect:/shop/login";

             }


        }else{
            session.setAttribute("changeError","用户名或密码错误");
            return "redirect:/shop/changePassword";
        }
    }
}
