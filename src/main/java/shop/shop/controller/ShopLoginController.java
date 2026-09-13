package shop.shop.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import shop.admin.Bean.User;
import shop.admin.mapper.CartMapper;
import shop.admin.mapper.UserMapper;
import shop.admin.tools.MD5passEncryption;
import shop.shop.tools.StringContainsMultipleTypes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Objects;

@Controller
public class ShopLoginController {
    @Autowired
    UserMapper userMapper;

    @Autowired
    CartMapper cartMapper;

    @GetMapping("/shop/login")
    public String shopLogin(){
        return "/shop/login";
    }

    @PostMapping("/shop/login")
    public String validateUser(String username, String password, HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        User user = userMapper.getUserByUsername(username);
        if(user==null){
            m.addAttribute("loginMistake","该用户名不存在");
            return "/shop/login";

        }
        String encryptedPassword = MD5passEncryption.encrypt(password);
        if (encryptedPassword.equals(user.getPassword())){
            session.setAttribute("shopusername",username);
            session.setAttribute("province",user.getProvince());
            session.setAttribute("city",user.getCity());
            session.setAttribute("area",user.getArea());
            session.setAttribute("school",user.getSchool());
            m.addAttribute("shopusername",session.getAttribute("shopusername"));
            return "redirect:/shop/index";
        }else{
            m.addAttribute("loginMistake","用户名或密码不正确");
            return "/shop/login";
        }
    }

    @PostMapping("/shop/signup")
    public String signup(String username, String password, String confirmpassword,HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        if(username.length()>8){
            m.addAttribute("signUpMistake","用户名过长");
            return "/shop/signup";
        }else if(!StringContainsMultipleTypes.stringJudgeIfContainTwoType(password)){
            m.addAttribute("signUpMistake","密码格式不符");
            return "/shop/signup";
        }else if(!Objects.equals(confirmpassword, password)){
            m.addAttribute("signUpMistake","两次密码不一致");
            return "/shop/signup";
        }else if(password.length()<8||password.length()>16){
            m.addAttribute("signUpMistake","密码格式不符");
            return "/shop/signup";
        }else{
            String encodedPassword = MD5passEncryption.encrypt(password);
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
        return "/shop/signup";
    }

    @GetMapping("/shop/exit")
    public String shopExit(HttpServletRequest request){
        HttpSession session = request.getSession();
        session.removeAttribute("shopusername");
        session.removeAttribute("province");
        session.removeAttribute("city");
        session.removeAttribute("area");
        session.removeAttribute("school");
        return "redirect:/shop/login";
    }

    @GetMapping("/shop/changePassword")
    public String changePassword(HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        String changeError = (String) session.getAttribute("changeError");
        m.addAttribute("changeError",changeError);
        session.removeAttribute("changeError");
        return "/shop/changePassword";
    }

    @PostMapping("/shop/changePassword")
    public String changePass(String username, String oldpassword,String newpassword, String confirmpassword,HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        User user = userMapper.getUserByUsername(username);
        if(user == null){
            session.setAttribute("changeError","用户名或密码错误");
            return "redirect:/shop/changePassword";
        }
        if(MD5passEncryption.encrypt(oldpassword).equals(user.getPassword())){
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
                 String md5Password = MD5passEncryption.encrypt(newpassword);
                 userMapper.changePassword(username,md5Password);
                 return "redirect:/shop/login";

             }


        }else{
            session.setAttribute("changeError","用户名或密码错误");
            return "redirect:/shop/changePassword";
        }
    }
}
