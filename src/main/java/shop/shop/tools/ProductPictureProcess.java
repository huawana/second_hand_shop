package shop.shop.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 商品图片落盘工具。
 *
 * <p>【清理】原类里注入了 4 个 Mapper（CartMapper/ProductMapper/SearchMapper/UserMapper）
 * 却一个都没用到 —— 典型的「为了使用 @Autowired 而使用」，且用静态字段接收实例依赖，
 * 这类写法在容器重启/多实例场景下会串数据。既然方法都是静态的，直接做成静态工具类。
 */
@Slf4j
public class ProductPictureProcess {

    private ProductPictureProcess() {
        // 工具类禁止实例化
    }

    public static void saveFile(String username, MultipartFile image, String filePath) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("上传的图片不能为空");
        }
        Path path = Paths.get(filePath);
        try {
            // 【修复】原代码直接 Files.copy，若上传目录不存在会抛 NoSuchFileException。
            // 首次部署到新机器（upload.path 指向的目录还没建）必然踩到，这里补上建目录。
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(image.getInputStream(), path, StandardCopyOption.REPLACE_EXISTING);
            log.info("用户[{}]上传图片 -> {}", username, path.toAbsolutePath());
        } catch (IOException e) {
            // 【修复】原代码把原始异常整个丢弃（只 new 了一个 RuntimeException），
            // 排障时完全不知道根因是磁盘满、权限不足还是路径非法。这里必须带上 cause。
            throw new RuntimeException("保存文件时发生错误: " + path.toAbsolutePath(), e);
        }
    }


}
