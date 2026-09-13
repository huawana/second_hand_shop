package shop.shop.tools;

import org.springframework.ui.Model;
import shop.admin.Bean.Product;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.List;

public class SessionCheck {
    public static boolean checkSessionName(HttpSession session){
        if (session.getAttribute("shopusername") == null) {
            session.setAttribute("shopusername", "请登录");
        }
        return session.getAttribute("shopusername").equals("请登录");
    }

    public static void checkSessionSchool(HttpSession session, Model m){
        if (session.getAttribute("school") != "null") {
            m.addAttribute("school", session.getAttribute("school"));
        } else {
            m.addAttribute("school", "空");
        }
    }


    public static void checkSessionPosition(HttpSession session, Model m){
        if(session.getAttribute("province")!="null"){
            m.addAttribute("province",session.getAttribute("province"));
            m.addAttribute("city",session.getAttribute("city"));
            m.addAttribute("area",session.getAttribute("area"));
        }else{
            m.addAttribute("province","空");
            m.addAttribute("city","空");
            m.addAttribute("area","空");
        }
    }
}
