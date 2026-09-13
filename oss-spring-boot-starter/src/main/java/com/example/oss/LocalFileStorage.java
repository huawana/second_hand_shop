package com.example.oss;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘存储实现。
 *
 * <p>相比「直接 Files.copy」，这里做了三件生产环境必须做的事：
 * <ol>
 *   <li><b>路径穿越防护</b>：objectKey 若被构造成 {@code ../../etc/passwd}，
 *       朴素写法会把文件写到配置目录之外。这里先 normalize 再断言结果仍在根目录内，
 *       不满足就拒绝 —— 文件名来自用户输入，必须当作不可信数据处理。</li>
 *   <li><b>目录自动创建</b>：全新机器上配置的目录通常还不存在，
 *       不建目录会抛 NoSuchFileException（本项目 Phase 0 就踩过这个坑）。</li>
 *   <li><b>异常带上根因</b>：把原始 IOException 作为 cause 抛出去，
 *       否则排障时分不清是磁盘满、权限不足还是路径非法。</li>
 * </ol>
 */
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final OssProperties properties;

    public LocalFileStorage(OssProperties properties) {
        this.properties = properties;
    }

    @Override
    public String store(MultipartFile file, String objectKey) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传的文件不能为空");
        }
        Path target = resolveSafely(objectKey);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String url = properties.normalizedUrlPrefix() + normalizeKey(objectKey);
            log.info("文件已保存 -> {}（对外地址 {}）", target.toAbsolutePath(), url);
            return url;
        } catch (IOException e) {
            throw new IllegalStateException("保存文件失败: " + target.toAbsolutePath(), e);
        }
    }

    @Override
    public boolean delete(String publicUrl) {
        if (publicUrl == null || !publicUrl.startsWith(properties.normalizedUrlPrefix())) {
            // 不属于本存储管理的地址（例如历史数据里的外链），不越权删除
            return false;
        }
        String key = publicUrl.substring(properties.normalizedUrlPrefix().length());
        Path target;
        try {
            target = resolveSafely(key);
        } catch (IllegalArgumentException e) {
            log.warn("拒绝删除非法路径 {}: {}", publicUrl, e.getMessage());
            return false;
        }
        try {
            boolean deleted = Files.deleteIfExists(target);
            if (!deleted) {
                log.warn("待删除文件不存在: {}", target.toAbsolutePath());
            }
            return deleted;
        } catch (IOException e) {
            log.warn("删除文件失败: {}", target.toAbsolutePath(), e);
            return false;
        }
    }

    /**
     * 把 objectKey 解析成绝对路径，并确保结果落在配置的根目录内。
     *
     * <p>这是防「路径穿越」的关键：仅在字符串层面过滤 {@code ..} 是不够的
     * （还有 URL 编码、符号链接等花样），必须用解析后的<b>规范化路径</b>做前缀判断。
     */
    private Path resolveSafely(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey 不能为空");
        }
        Path root = Paths.get(properties.getLocalDir()).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的 objectKey（疑似路径穿越）: " + objectKey);
        }
        return target;
    }

    /** 统一分隔符，保证 URL 里不会出现 Windows 的反斜杠。 */
    private static String normalizeKey(String objectKey) {
        return objectKey.replace('\\', '/').replaceAll("^/+", "");
    }
}
