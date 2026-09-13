package com.example.oss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LocalFileStorage} 单元测试。
 *
 * <p>用 {@code @TempDir} 拿真实临时目录、走真实文件 IO —— 存储这类「和文件系统打交道」
 * 的逻辑，mock 掉 Files 反而测不出路径拼接、目录创建这些真正容易错的地方。
 */
class LocalFileStorageTest {

    private static OssProperties props(Path dir, String urlPrefix) {
        OssProperties p = new OssProperties();
        p.setType("local");
        p.setLocalDir(dir.toString());
        p.setUrlPrefix(urlPrefix);
        return p;
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("image", name, "image/png", content);
    }

    @Test
    @DisplayName("保存文件：落盘成功并返回 urlPrefix + objectKey 的地址")
    void store_writesFileAndReturnsUrl(@TempDir Path dir) throws Exception {
        LocalFileStorage storage = new LocalFileStorage(props(dir, "/shop/assets/product-img/"));

        String url = storage.store(file("a.png", "PNGDATA".getBytes()), "123.png");

        assertThat(url).isEqualTo("/shop/assets/product-img/123.png");
        assertThat(Files.readString(dir.resolve("123.png"))).isEqualTo("PNGDATA");
    }

    @Test
    @DisplayName("目录不存在时自动创建（全新机器首次上传的场景）")
    void store_createsMissingDirectories(@TempDir Path dir) {
        Path nested = dir.resolve("a/b/c");
        LocalFileStorage storage = new LocalFileStorage(props(nested, "/img/"));

        String url = storage.store(file("x.png", "X".getBytes()), "x.png");

        assertThat(nested.resolve("x.png")).exists();
        assertThat(url).isEqualTo("/img/x.png");
    }

    @Test
    @DisplayName("objectKey 支持子目录，且在 URL 中统一用正斜杠")
    void store_supportsSubDirectoryAndNormalizesSeparators(@TempDir Path dir) throws Exception {
        LocalFileStorage storage = new LocalFileStorage(props(dir, "/img/"));

        String url = storage.store(file("y.png", "Y".getBytes()), "2026\\09\\y.png");

        assertThat(url).isEqualTo("/img/2026/09/y.png");
        assertThat(dir.resolve("2026").resolve("09").resolve("y.png")).exists();
    }

    @Test
    @DisplayName("路径穿越被拒绝（文件名来自用户输入，必须当作不可信数据）")
    void store_rejectsPathTraversal(@TempDir Path dir) {
        Path root = dir.resolve("root");
        LocalFileStorage storage = new LocalFileStorage(props(root, "/img/"));

        assertThatThrownBy(() -> storage.store(file("evil.png", "E".getBytes()), "../../evil.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径穿越");
        assertThat(dir.resolve("evil.png")).doesNotExist();
    }

    @Test
    @DisplayName("空文件与空 objectKey 被拒绝")
    void store_rejectsEmptyInput(@TempDir Path dir) {
        LocalFileStorage storage = new LocalFileStorage(props(dir, "/img/"));

        assertThatThrownBy(() -> storage.store(file("empty.png", new byte[0]), "empty.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> storage.store(file("a.png", "A".getBytes()), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objectKey");
    }

    @Test
    @DisplayName("按 URL 删除：属于本存储的文件被删掉并返回 true")
    void delete_removesOwnFile(@TempDir Path dir) {
        LocalFileStorage storage = new LocalFileStorage(props(dir, "/img/"));
        String url = storage.store(file("d.png", "D".getBytes()), "d.png");

        assertThat(storage.delete(url)).isTrue();
        assertThat(dir.resolve("d.png")).doesNotExist();
    }

    @Test
    @DisplayName("删除不存在的文件返回 false 而不抛异常（幂等，便于清理逻辑直接调用）")
    void delete_isIdempotent(@TempDir Path dir) {
        LocalFileStorage storage = new LocalFileStorage(props(dir, "/img/"));
        assertThat(storage.delete("/img/not-there.png")).isFalse();
    }

    @Test
    @DisplayName("不删除不属于本存储的 URL（避免误删其他来源的文件）")
    void delete_ignoresForeignUrl(@TempDir Path dir) {
        LocalFileStorage storage = new LocalFileStorage(props(dir, "/img/"));
        assertThat(storage.delete("https://cdn.example.com/x.png")).isFalse();
        assertThat(storage.delete(null)).isFalse();
    }

    @Test
    @DisplayName("urlPrefix 容错：缺少首尾斜杠时自动补齐")
    void urlPrefix_isNormalized() {
        OssProperties p = new OssProperties();
        p.setUrlPrefix("img/product");
        assertThat(p.normalizedUrlPrefix()).isEqualTo("/img/product/");

        p.setUrlPrefix("/img/product/");
        assertThat(p.normalizedUrlPrefix()).isEqualTo("/img/product/");

        p.setUrlPrefix("  /a/  ");
        assertThat(p.normalizedUrlPrefix()).isEqualTo("/a/");

        p.setUrlPrefix(null);
        assertThat(p.normalizedUrlPrefix()).isEqualTo("/");
    }

    @Test
    @DisplayName("前缀容错后仍能正确拼接 URL 与反查删除")
    void urlPrefix_normalizationAffectsStoreAndDelete(@TempDir Path dir) {
        // 故意不写首尾斜杠，验证配置容错不会导致 URL 拼坏
        LocalFileStorage storage = new LocalFileStorage(props(dir, "img/product"));

        String url = storage.store(file("n.png", "N".getBytes()), "n.png");

        assertThat(url).isEqualTo("/img/product/n.png");
        assertThat(storage.delete(url)).isTrue();
    }

    @Test
    @DisplayName("默认配置开箱可用（type=local、目录与前缀都有默认值）")
    void defaultProperties() {
        OssProperties p = new OssProperties();
        assertThat(p.getType()).isEqualTo("local");
        assertThat(p.getLocalDir()).isNotBlank();
        assertThat(p.normalizedUrlPrefix()).isEqualTo("/upload/");
    }
}
