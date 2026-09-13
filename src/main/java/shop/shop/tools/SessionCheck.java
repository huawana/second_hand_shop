package shop.shop.tools;

import org.springframework.ui.Model;
import shop.admin.Bean.Product;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;

public class SessionCheck {
    public static boolean checkSessionName(HttpSession session){
        if (session.getAttribute("shopusername") == null) {
            session.setAttribute("shopusername", "请登录");
        }
        return session.getAttribute("shopusername").equals("请登录");
    }

    public static void checkSessionSchool(HttpSession session, Model m){
        // 【Bug 修复】原为 session.getAttribute("school") != "null"：
        // 这是拿 Object 与字符串字面量比较「引用」，两者永远不相等 → 条件恒为 true，
        // 下面的 else 分支其实是死代码。这里的意图显然是判空，改为标准写法。
        // （面试可讲：== / != 比较引用，字符串内容相等必须用 equals）
        if (session.getAttribute("school") != null) {
            m.addAttribute("school", session.getAttribute("school"));
        } else {
            m.addAttribute("school", "空");
        }
    }


    public static void checkSessionPosition(HttpSession session, Model m){
        // 【Bug 修复】同上：!= "null" 恒为 true，else 分支是死代码
        if(session.getAttribute("province")!=null){
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
