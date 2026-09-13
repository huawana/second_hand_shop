package shop.admin.controller;

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
import org.springframework.security.access.prepost.PreAuthorize;
import shop.admin.Bean.Product;
import shop.admin.Bean.User;
import shop.admin.mapper.ProductMapper;
import shop.admin.mapper.UserMapper;
import shop.security.CurrentUser;
import shop.shop.tools.ProductPictureProcess;

import java.util.List;

@Slf4j
// 【Phase 1】后台商品管理：仅 ADMIN 可访问（方法级鉴权，与 URL 规则形成纵深防御）
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class ProductController {
    @Autowired
    ProductMapper productMapper;
    @Autowired
    UserMapper userMapper;

    @Value("${upload.path}")
    private String uploadPath;

    // 鉴权由安全层统一负责（URL 规则 + 类级 @PreAuthorize，均基于 JWT），不再自行判 session
    @GetMapping("/admin/product")
    public String product(Model m){
        List<Product> productList = productMapper.getProducts("");
        m.addAttribute("products",productList);
        return "admin/product";
    }

    /**
     * 【Bug 修复】原方法只返回视图名 "/admin/product_add"，但 templates/admin/ 下
     * 并没有 product_add.html → 点击必然 500。现补齐页面，并把卖家列表带到前端供选择。
     */
    @GetMapping("/admin/product_add")
    public String productAdd(Model m){
        m.addAttribute("users", userMapper.getUsers());
        return "admin/product_add";
    }

    /**
     * 【新增】原项目有 product_add 页面入口但没有对应的提交处理，页面本身也是死的。
     * 这里补齐后台新增商品链路：校验 → 存图 → 落库。
     */
    @PostMapping("/admin/product_add")
    public String doProductAdd(@RequestParam("image") MultipartFile image,
                               @RequestParam("name") String name,
                               @RequestParam("price") double price,
                               @RequestParam("description") String description,
                               @RequestParam("sellerUsername") String sellerUsername,
                               Model m){
        User seller = userMapper.getUserByUsername(sellerUsername);
        if (seller == null) {
            m.addAttribute("result", "卖家不存在：" + sellerUsername);
        } else if (price <= 0) {
            m.addAttribute("result", "商品价格必须大于0");
        } else if (image == null || image.isEmpty()) {
            m.addAttribute("result", "必须上传商品图片");
        } else {
            try {
                // 注意：这里用「当前最大 id + 1」当文件名，属于典型的 ID 生成竞态 ——
                // 并发上传会拿到同一个文件名并互相覆盖。Phase 6 引入雪花算法后替换。
                // 另外 getNowId() 在空表时返回 null，拆箱会抛 NPE（同样在 Phase 6 一并处理）。
                int nowId = productMapper.getNowId();
                String fileName = String.valueOf(nowId + 1) + ".png";
                ProductPictureProcess.saveFile(sellerUsername, image, uploadPath + fileName);
                productMapper.uploadProduct(name, seller.getId(), description, price,
                        "/shop/assets/product-img/" + fileName);
                log.info("管理员新增商品 name={} seller={} file={}", name, sellerUsername, fileName);
                m.addAttribute("result", "添加商品成功");
            } catch (Exception e) {
                log.error("添加商品失败 name={} seller={}", name, sellerUsername, e);
                m.addAttribute("result", "添加商品失败：" + e.getMessage());
            }
        }
        m.addAttribute("users", userMapper.getUsers());
        return "admin/product_add";
    }

    @GetMapping("/admin/product_delete/{id}")
    public String productDelete(@PathVariable("id") int id,Model m){
        try{
            productMapper.deleteProduct(id);
            log.info("管理员删除商品 id={}", id);
            m.addAttribute("result","删除商品成功");
            return "redirect:/admin/product";
        }catch (Exception e){
            log.error("删除商品失败 id={}", id, e);
            m.addAttribute("result","删除商品失败");
            return "redirect:/admin/product";
        }
    }
}
