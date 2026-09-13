package shop.shop.controller;

import lombok.extern.slf4j.Slf4j;
import com.example.oss.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
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
import shop.common.cache.CacheInvalidator;
import shop.common.cache.ProductBloomFilter;
import shop.shop.tools.SessionCheck;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Slf4j
@Controller
public class ShopSaleController {

    @Autowired
    ProductMapper productMapper;
    @Autowired
    UserMapper userMapper;

    /**
     * 【Phase 1 收尾】文件存储由自定义 starter `oss-spring-boot-starter` 自动配置注入。
     * 本类不再关心「存到哪、怎么存、目录存不存在」—— 换对象存储时这里零改动。
     */
    @Autowired
    FileStorage fileStorage;

    /** 【Phase 3.4】新增商品后要告知布隆过滤器 */
    @Autowired
    ProductBloomFilter productBloomFilter;

    /** 【Phase 3.7】改完商品后删缓存（含延迟双删） */
    @Autowired
    CacheInvalidator cacheInvalidator;

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
        String imgPath = fileStorage.store(image, fileName);
        productMapper.uploadProduct(name,uid,description,price,imgPath);
        // 【Phase 3.4】新增商品后必须主动告知布隆过滤器，否则新商品的 id 会被判成「一定不存在」，
        // 详情页直接 404。见 ProductBloomFilter 类注释的「新增必须主动告知」。
        Integer newId = productMapper.getNowId();
        if (newId != null) {
            productBloomFilter.add(newId);
        }
        log.info("用户[{}]发布商品 name={} price={} file={} newId={}", username, name, price, fileName, newId);
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
            // 【Phase 3.7】先更库、再删缓存（这条 mapper 调用是自动提交的，返回即已落库）。
            // 用 CacheInvalidator 而不是直接 delete：它还会补一次延迟删除，
            // 处理「读请求在删缓存前读到旧值、删缓存后才回写」的竞态，见其类注释。
            cacheInvalidator.evictProduct(id);
        }else{
            // 【Bug 修复】原代码 new File(imgPath) 直接把 URL 当磁盘路径用。
            // imgPath 形如 "/shop/assets/product-img/123.png"，会被解析成「当前工作目录下的
            // shop/assets/...」→ 根本指向不到真实文件，delete() 恒失败，旧图永久残留在磁盘。
            // 正确做法：从 URL 里取出文件名，再拼上传目录。
            // 【Bug 修复】原代码 new File(imgPath) 直接把 URL 当磁盘路径用 → 永远删不到真实文件。
            // 【Phase 1 收尾】删除也交给 FileStorage：它按 URL 反查真实位置，
            // 本地实现截取相对路径、云实现调 SDK 删除，调用方始终只有这一行。
            if (!fileStorage.delete(origin.getImgPath())) {
                log.warn("旧图片删除失败或文件不存在: {}", origin.getImgPath());
            }

            // 【Bug 修复】原代码把新图存成 "{id}.png"，但写回数据库时用的仍是旧的 imgPath，
            // 一旦旧文件名与 id 不对应，就会出现「库里有路径、磁盘没文件」的坏数据。
            // 这里统一：文件名 = {id}.png，并把 store() 返回的同一个值写回数据库。
            String newImgPath = fileStorage.store(image, id + ".png");
            productMapper.updateProduct(name,description,price, newImgPath);
            // 【Phase 3.7】换了图片也要失效缓存，否则详情页会一直显示旧图（浏览器缓存的 imgPath 是旧的）
            cacheInvalidator.evictProduct(id);
        }
        log.info("用户[{}]修改商品 id={} name={} price={}", username, id, name, price);
        return "redirect:/shop/personRelease";

    }
}
