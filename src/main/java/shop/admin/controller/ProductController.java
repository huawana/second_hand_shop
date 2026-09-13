package shop.admin.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import shop.admin.Bean.Product;
import shop.admin.mapper.ProductMapper;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.List;

@Controller
public class ProductController {
    @Autowired
    ProductMapper productMapper;

    @GetMapping("/admin/product")
    public String product(Model m, HttpServletRequest request){
        HttpSession session = request.getSession();
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
        List<Product> productList = productMapper.getProducts("");
        m.addAttribute("products",productList);
        return "/admin/product";
    }

    @GetMapping("/admin/product_add")
    public String productAdd(HttpServletRequest request){
        HttpSession session = request.getSession();
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
        return "/admin/product_add";
    }

    @GetMapping("/admin/product_delete/{id}")
    public String productDelete(@PathVariable("id") int id,Model m,HttpSession session){
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
        try{
            productMapper.deleteProduct(id);
            m.addAttribute("result","删除商品成功");
            return "redirect:/admin/product";
        }catch (Exception e){
            e.printStackTrace();
            m.addAttribute("result","删除商品失败");
            return "redirect:/admin/product";
        }
    }
}
