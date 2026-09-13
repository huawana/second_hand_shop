package shop.admin.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import shop.admin.Bean.Admin;
import shop.admin.mapper.AdminMapper;
import shop.admin.tools.MD5passEncryption;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Date;
import java.util.List;
import java.util.Objects;

@Controller
public class LoginController {

    @Autowired
    AdminMapper adminMapper;
    @GetMapping("/admin/login")
    public String login(HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        String error = (String)session.getAttribute("loginError");
        m.addAttribute("loginError",error);
        session.removeAttribute("loginError");
        return "admin/login";
    }

    @PostMapping("/admin/loginResult")
    public String confirmPass(String adminuser, String adminpass, HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        List<Admin> adminList = adminMapper.getAdmins();
        String encryptedPassword = MD5passEncryption.encrypt(adminpass);
        for (Admin admin:adminList) {
            if(Objects.equals(admin.getAdminuser(), adminuser)&& encryptedPassword.equals(admin.getAdminpass())){
                session.setAttribute("adminuser", adminuser);
                adminMapper.UpdateAdminLoginTime(adminuser, new Date());
                m.addAttribute("admin",admin.getAdminuser());
                return "redirect:/admin/admin";
            }
        }
        session.setAttribute("loginError","用户名或密码错误");
        return "redirect:/admin/login";
    }
    @GetMapping("/admin/logout")
    public String logout(HttpServletRequest request){
        HttpSession session = request.getSession();
        session.removeAttribute("adminuser");
        return "redirect:/admin/login";
    }
}