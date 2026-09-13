package shop.shop.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import shop.admin.Bean.Product;
import shop.admin.mapper.ProductMapper;
import shop.admin.mapper.UserMapper;
import shop.shop.tools.ProductPictureProcess;
import shop.shop.tools.SessionCheck;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Controller
public class ShopSaleController {

    @Autowired
    ProductMapper productMapper;
    @Autowired
    UserMapper userMapper;
    @Value("${upload.path}") // 从配置文件中获取上传路径
    private String uploadPath;
    @PostMapping("/shop/uploadProduct")
    public String uploadProduct(HttpServletRequest request, Model m, @RequestParam("image") MultipartFile image, @RequestParam("name") String name, @RequestParam("price") double price, @RequestParam("description") String description){
        HttpSession session = request.getSession();
        System.out.println(1111);
        if (SessionCheck.checkSessionName(session)) {
            return "redirect:/shop/login";
        }
        if(price <= 0){
            System.out.println(2222);
            session.setAttribute("saleError","商品价格必须大于0");
            return "redirect:/shop/sale";
        }
        System.out.println(3333);
        String username = (String)session.getAttribute("shopusername");
        m.addAttribute("shopusername", session.getAttribute("shopusername"));
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
        int uid = userMapper.getIdByUserName(username);
        int nowId = productMapper.getNowId();
        String fileName = String.valueOf(nowId + 1) + ".png";
        System.out.println(fileName);
        String filePath = uploadPath + fileName;
        System.out.println(filePath);
        ProductPictureProcess.saveFile(username,image,filePath);
        productMapper.uploadProduct(name,uid,description,price,"/shop/assets/product-img/"+fileName);
        return "redirect:/shop/index";

    }
    @GetMapping("/shop/changeProductInformation/{id}")
    public String changeProductInformation(@PathVariable int id,HttpServletRequest request, Model m){
        System.out.println(6666);
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            return "redirect:/shop/login";
        }
        if(session.getAttribute("saleError")=="商品价格必须大于0"){
            m.addAttribute("saleError","商品价格必须大于0");
            session.removeAttribute("saleError");
            return "redirect:/shop/changeProductInformation";
        }
        String username = (String)session.getAttribute("shopusername");
        m.addAttribute("shopusername", session.getAttribute("shopusername"));
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
        Product product = productMapper.getProductById(id);
        String sellerName = product.getSellerName();
        if(!sellerName.equals(username)){
            return "redirect:/shop/index";
        }else{
            m.addAttribute("product",product);
            return "/shop/changeProductInformation";
        }
    }

    @PostMapping("/shop/changeProduct")
    public String changeProduct(HttpServletRequest request, Model m, RedirectAttributes redirectAttributes, @RequestParam("id") int id, @RequestParam("image") MultipartFile image, @RequestParam("name") String name, @RequestParam("price") double price, @RequestParam("description") String description, HttpServletResponse response){
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            return "redirect:/shop/login";
        }
        String username = (String)session.getAttribute("shopusername");
        m.addAttribute("shopusername", session.getAttribute("shopusername"));
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
        if(image == null || image.isEmpty()){
            String imgPath = productMapper.getProductById(id).getImgPath();
            productMapper.updateProduct(name,description,price,imgPath);
        }else{
            Product product = productMapper.getProductById(id);
            String imgPath = product.getImgPath();
            File file = new File(imgPath);
            file.delete();
            // 在这里处理接收到的数据
            // 例如：保存图片文件路径、创建商品对象等
            int uid = userMapper.getIdByUserName(username);
            String filePath = uploadPath + id + ".png";
            ProductPictureProcess.saveFile(username,image,filePath);
            productMapper.updateProduct(name,description,price,imgPath);
        }
        return "redirect:/shop/personRelease";

    }
}
