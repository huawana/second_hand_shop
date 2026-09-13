package shop.shop.tools;

import org.apache.tomcat.jni.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import shop.admin.mapper.CartMapper;
import shop.admin.mapper.ProductMapper;
import shop.admin.mapper.UserMapper;
import shop.shop.mapper.SearchMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Component
public class ProductPictureProcess {
    static CartMapper cartMapper;
    static ProductMapper productMapper;
    static SearchMapper searchMapper;
    static UserMapper userMapper;
    @Autowired
    public ProductPictureProcess(CartMapper cartMapper,ProductMapper productMapper,SearchMapper searchMapper,UserMapper userMapper) {
        this.cartMapper = cartMapper;
        this.productMapper = productMapper;
        this.searchMapper = searchMapper;
        this.userMapper = userMapper;
    }
    private static final Logger logger = LoggerFactory.getLogger(ProductPictureProcess.class);

    public static void saveFile(String username, MultipartFile image, String filePath) {


        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("上传的图片不能为空");
        }

        // 构造保存文件的完整路径，这里假设你想在路径中包含用户名
        Path path = Paths.get(filePath);
        System.out.println(filePath);
        System.out.println(path);
        try {
            // 保存文件到指定路径
            Files.copy(image.getInputStream(), path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("保存文件时发生错误", e);
        }
    }


}
