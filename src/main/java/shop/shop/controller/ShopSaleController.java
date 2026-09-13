package shop.shop.controller;

import lombok.extern.slf4j.Slf4j;
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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.File;

@Slf4j
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
        if (SessionCheck.checkSessionName(session)) {
            return "redirect:/shop/login";
        }
        if(price <= 0){
            session.setAttribute("saleError","商品价格必须大于0");
            return "redirect:/shop/sale";
        }
        if(image == null || image.isEmpty()){
            // 与价格校验保持一致：这是用户输入问题，应该回到页面提示，而不是抛异常
            session.setAttribute("saleError","必须上传商品图片");
            return "redirect:/shop/sale";
        }
        String username = (String)session.getAttribute("shopusername");
        m.addAttribute("shopusername", session.getAttribute("shopusername"));
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
        int uid = userMapper.getIdByUserName(username);
        // 【Bug 修复】getNowId() 在商品表为空时返回 null，原代码直接拆箱会 NPE。
        // 【已知缺陷】用「当前最大 id + 1」当文件名是典型的 ID 生成竞态：
        //   (a) 并发上传会拿到同一个文件名并互相覆盖
        //   (b) 若删过商品，maxId+1 与自增主键会不一致
        // 这两点正好是 Phase 6「分布式 ID」章节的引入案例。
        Integer nowId = productMapper.getNowId();
        String fileName = String.valueOf(nowId == null ? 1 : nowId + 1) + ".png";
        String filePath = uploadPath + fileName;
        ProductPictureProcess.saveFile(username,image,filePath);
        productMapper.uploadProduct(name,uid,description,price,"/shop/assets/product-img/"+fileName);
        log.info("用户[{}]发布商品 name={} price={} file={}", username, name, price, fileName);
        return "redirect:/shop/index";

    }
    @GetMapping("/shop/changeProductInformation/{id}")
    public String changeProductInformation(@PathVariable int id,HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            return "redirect:/shop/login";
        }
        // 【Bug 修复】此处原有一段从 Sale() 复制的 saleError 判断，其中
        // "redirect:/shop/changeProductInformation" 缺少 {id} 路径变量，而映射只注册了
        // /shop/changeProductInformation/{id} → 必然 404。且 changeProduct(POST) 根本不会
        // 写入 saleError（该属性只属于 /shop/sale 上传流程），这段是永不成立的死代码，直接删除。
        String username = (String)session.getAttribute("shopusername");
        m.addAttribute("shopusername", session.getAttribute("shopusername"));
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
        Product product = productMapper.getProductById(id);
        if (product == null) {
            return "redirect:/shop/personRelease";
        }
        String sellerName = product.getSellerName();
        if(!sellerName.equals(username)){
            // 越权保护：不是自己的商品不能编辑
            return "redirect:/shop/index";
        }else{
            m.addAttribute("product",product);
            return "shop/changeProductInformation";
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
        if(price <= 0){
            session.setAttribute("saleError","商品价格必须大于0");
            return "redirect:/shop/changeProductInformation/" + id;
        }
        // 越权保护：只能修改自己发布的商品，否则任何人都能改他人商品的价格
        Product origin = productMapper.getProductById(id);
        if (origin == null) {
            return "redirect:/shop/personRelease";
        }
        if (!username.equals(origin.getSellerName())) {
            return "redirect:/shop/index";
        }
        if(image == null || image.isEmpty()){
            // 未上传新图片：沿用原图路径
            productMapper.updateProduct(name,description,price,origin.getImgPath());
        }else{
            // 【Bug 修复】原代码 new File(imgPath) 直接把 URL 当磁盘路径用。
            // imgPath 形如 "/shop/assets/product-img/123.png"，会被解析成「当前工作目录下的
            // shop/assets/...」→ 根本指向不到真实文件，delete() 恒失败，旧图永久残留在磁盘。
            // 正确做法：从 URL 里取出文件名，再拼上传目录。
            String imgPath = origin.getImgPath();
            String oldFileName = imgPath.substring(imgPath.lastIndexOf('/') + 1);
            File oldFile = new File(uploadPath + oldFileName);
            if (oldFile.exists() && !oldFile.delete()) {
                log.warn("旧图片删除失败: {}", oldFile.getAbsolutePath());
            }

            // 【Bug 修复】原代码把新图存成 "{id}.png"，但写回数据库时用的仍是旧的 imgPath，
            // 一旦旧文件名与 id 不对应，就会出现「库里有路径、磁盘没文件」的坏数据。
            // 这里统一：文件名 = {id}.png，并把同一个值写回数据库。
            String newFileName = String.valueOf(id) + ".png";
            ProductPictureProcess.saveFile(username,image,uploadPath + newFileName);
            productMapper.updateProduct(name,description,price,
                    "/shop/assets/product-img/" + newFileName);
        }
        log.info("用户[{}]修改商品 id={} name={} price={}", username, id, name, price);
        return "redirect:/shop/personRelease";

    }
}
