package shop.admin.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import shop.admin.Bean.Admin;
import shop.admin.mapper.AdminMapper;
import shop.admin.tools.MD5passEncryption;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Objects;

@Controller
public class AdminController {
    @Autowired
    AdminMapper adminMapper;

    @PostMapping("/admin/adminEdit")
    public String getAdmin(HttpServletRequest request, @RequestParam("adminuser")String adminuser,@RequestParam("password")String password, @RequestParam("newpass")String newpass, @RequestParam("confirmpass")String confirmpass){
        HttpSession session = request.getSession();
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
        Admin admin = adminMapper.getAdmin(adminuser);
        // 【Bug 修复】原代码未判空，adminuser 不存在时 admin 为 null，下一行直接 NPE
        if (admin == null) {
            return "redirect:/admin/admin_edit";
        }
        if(!Objects.equals(admin.getAdminpass(), MD5passEncryption.encrypt(password))){
            return "redirect:/admin/admin_edit";
        } else if (!Objects.equals(newpass, confirmpass)) {
            return "redirect:/admin/admin_edit";
        }else{
            adminMapper.UpdateAdminPassword(adminuser,MD5passEncryption.encrypt(newpass));
        }
        return "redirect:/admin/admin";
    }



    @GetMapping("/admin/admin")
    public String admin(Model m, HttpServletRequest request){
        HttpSession session = request.getSession();
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
        List<Admin> adminList = adminMapper.getAdmins();
        m.addAttribute("admins",adminList);
        return "admin/admin";
    }

    @GetMapping("/admin/admin_edit")
    public String adminEdit(Model m, HttpServletRequest request){
        HttpSession session = request.getSession();
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
        m.addAttribute("adminuser",session.getAttribute("adminuser"));
        return "admin/admin_edit";
    }
}
