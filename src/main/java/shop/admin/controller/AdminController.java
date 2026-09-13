package shop.admin.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import shop.admin.Bean.Admin;
import shop.admin.mapper.AdminMapper;
import shop.security.CurrentUser;
import shop.security.PasswordService;

import java.util.List;
import java.util.Objects;

@Controller
// 【Phase 1】方法级鉴权：类上统一声明，等价于给每个方法都加上权限校验。
// 安全链里已对 /admin/** 配了 hasRole("ADMIN")（URL 层），这里是方法层，形成纵深防御。
// 好处：将来有人改了 URL 规则或新增了绕过路径，方法级注解依然守得住。
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    @Autowired
    AdminMapper adminMapper;

    /** 【Phase 1】兼容 MD5/BCrypt 的密码校验与编码，替换原来的 MD5passEncryption 直接比对 */
    @Autowired
    PasswordService passwordService;

    @PostMapping("/admin/adminEdit")
    public String getAdmin(@RequestParam("adminuser")String adminuser,@RequestParam("password")String password, @RequestParam("newpass")String newpass, @RequestParam("confirmpass")String confirmpass){
        Admin admin = adminMapper.getAdmin(adminuser);
        // 【Bug 修复】原代码未判空，adminuser 不存在时 admin 为 null，下一行直接 NPE
        if (admin == null) {
            return "redirect:/admin/admin_edit";
        }
        // 【Phase 1】改用 PasswordService：兼容存量 MD5，新密码一律 BCrypt
        if(!passwordService.matches(password, admin.getAdminpass())){
            return "redirect:/admin/admin_edit";
        } else if (!Objects.equals(newpass, confirmpass)) {
            return "redirect:/admin/admin_edit";
        }else{
            adminMapper.UpdateAdminPassword(adminuser,passwordService.encode(newpass));
        }
        return "redirect:/admin/admin";
    }



    // 鉴权由安全层统一负责（URL 规则 + 类级 @PreAuthorize，均基于 JWT），
    // 这里不再自行用 session 判断，避免出现「两份真相」。
    @GetMapping("/admin/admin")
    public String admin(Model m){
        List<Admin> adminList = adminMapper.getAdmins();
        m.addAttribute("admins",adminList);
        return "admin/admin";
    }

    @GetMapping("/admin/admin_edit")
    public String adminEdit(Model m){
        // 当前管理员取自 JWT（SecurityContext），而不是 session
        m.addAttribute("adminuser", CurrentUser.username());
        return "admin/admin_edit";
    }
}
