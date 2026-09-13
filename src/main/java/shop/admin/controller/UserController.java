package shop.admin.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import shop.admin.Bean.User;
import shop.admin.mapper.UserMapper;
import shop.admin.tools.MD5passEncryption;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.sql.Date;
import java.util.List;
import java.util.Objects;

@Slf4j
@Controller
public class UserController {
    @Autowired
    UserMapper userMapper;

    @GetMapping("/admin/user")
    public String user(Model m){
        List<User> userList = userMapper.getUsers();
        m.addAttribute("users",userList);
        return "admin/user";
    }

    @PostMapping("/admin/result")
    public String addUser(String username, String password, String confirmpass, String email, String phone, Date createdAt,Model m,HttpServletRequest request) {
        HttpSession session = request.getSession();
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
        if(Objects.equals(password, confirmpass)){
            try{
                String encodedPassword = MD5passEncryption.encrypt(password);
                userMapper.addUser(username,encodedPassword,email,phone);
                log.info("管理员[{}]新增用户 {}", session.getAttribute("adminuser"), username);
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
    public String userAdd(HttpServletRequest request){
        HttpSession session = request.getSession();
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
        return "/admin/user_add";
    }


    @GetMapping("/admin/user_edit/{id}")
    public String getUser(@PathVariable("id") int id,Model m,HttpServletRequest request){
        HttpSession session = request.getSession();
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
        User user = userMapper.getUserById(id);
        session.setAttribute("userId",id);
        m.addAttribute("user",user);
        return "admin/user_edit";
    }

    @GetMapping("/admin/user_delete/{id}")
    public String deleteUser(@PathVariable("id") Long id, Model m, HttpServletRequest request){
        HttpSession session = request.getSession();
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
        try {
            userMapper.deleteUser(id);
            log.info("管理员[{}]删除用户 id={}", session.getAttribute("adminuser"), id);
            m.addAttribute("result","删除用户成功");
        }catch (Exception e){
            log.error("删除用户失败 id={}", id, e);
            m.addAttribute("result","删除用户失败");
        }
        return "redirect:/admin/user";
    }

    @PostMapping("/admin/userEdit")
    public String userEdit(HttpServletRequest request, @RequestParam("username") String username,@RequestParam("phone") String phone, @RequestParam("email") String email){
        HttpSession session = request.getSession();
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
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
