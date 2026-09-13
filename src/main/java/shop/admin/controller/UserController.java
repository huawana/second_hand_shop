package shop.admin.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import shop.admin.Bean.User;
import shop.admin.mapper.UserMapper;
import shop.security.CurrentUser;
import shop.security.PasswordService;

import jakarta.servlet.http.HttpSession;
import java.sql.Date;
import java.util.List;
import java.util.Objects;

@Slf4j
// 【Phase 1】后台用户管理：仅 ADMIN 可访问（方法级鉴权，与 URL 规则形成纵深防御）
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
    @Autowired
    UserMapper userMapper;

    /** 【Phase 1】新增用户时密码用 BCrypt 存储 */
    @Autowired
    PasswordService passwordService;

    @GetMapping("/admin/user")
    public String user(Model m){
        List<User> userList = userMapper.getUsers();
        m.addAttribute("users",userList);
        return "admin/user";
    }

    // 鉴权由安全层统一负责（URL 规则 + 类级 @PreAuthorize，均基于 JWT），不再自行判 session
    @PostMapping("/admin/result")
    public String addUser(String username, String password, String confirmpass, String email, String phone, Date createdAt,Model m) {
        if(Objects.equals(password, confirmpass)){
            try{
                String encodedPassword = passwordService.encode(password);
                userMapper.addUser(username,encodedPassword,email,phone);
                log.info("管理员[{}]新增用户 {}", CurrentUser.username(), username);
                m.addAttribute("result","添加用户成功");
            } catch (Exception e) {
                log.error("添加用户失败 username={}", username, e);
                m.addAttribute("result","添加用户失败");
            }
        }else {
            m.addAttribute("result","两次密码不一致");
        }

        return "redirect:/admin/user";
    }

    @GetMapping("/admin/user_add")
    public String userAdd(){
        return "admin/user_add";
    }


    @GetMapping("/admin/user_edit/{id}")
    public String getUser(@PathVariable("id") int id,Model m,HttpSession session){
        User user = userMapper.getUserById(id);
        // 保留 session 承载「一次性流程状态」：编辑失败时要回到同一个用户
        session.setAttribute("userId",id);
        m.addAttribute("user",user);
        return "admin/user_edit";
    }

    @GetMapping("/admin/user_delete/{id}")
    public String deleteUser(@PathVariable("id") Long id, Model m){
        try {
            userMapper.deleteUser(id);
            log.info("管理员[{}]删除用户 id={}", CurrentUser.username(), id);
            m.addAttribute("result","删除用户成功");
        }catch (Exception e){
            log.error("删除用户失败 id={}", id, e);
            m.addAttribute("result","删除用户失败");
        }
        return "redirect:/admin/user";
    }

    @PostMapping("/admin/userEdit")
    public String userEdit(HttpSession session, @RequestParam("username") String username,@RequestParam("phone") String phone, @RequestParam("email") String email){
        try {
            userMapper.updateUserByUserName(username, phone, email);
            return "redirect:/admin/user";
        }catch (Exception e){
            // 【Bug 修复】:68 处 session 里存的是 int（自动装箱为 Integer），此处强转 String 会抛
            // ClassCastException，把原本的失败原因掩盖掉。改用 Object 接收。
            Object uid = session.getAttribute("userId");
            return "redirect:/admin/user_edit/" + (uid == null ? "" : uid);
        }
    }
}
