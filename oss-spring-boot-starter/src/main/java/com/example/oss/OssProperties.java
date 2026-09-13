package com.example.oss;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件存储配置（{@code oss.*}）。
 *
 * <p>命名沿用业界习惯：即使当前实现是「本地磁盘」，配置前缀也用 {@code oss}
 * （object storage service）—— 因为对业务代码来说，它关心的只是「拿到一个可访问的 URL」，
 * 存储介质是本地还是云端属于实现细节，不应该体现在配置名里。
 * 这样将来从本地切到 MinIO/OSS，业务代码零改动、配置只是多填几项。
 *
 * <p>只声明「当前真正被用到」的字段。常见的新手错误是把 endpoint/bucket/accessKey
 * 这些云存储参数一股脑先写上，结果是一堆永远为 null 的死配置 ——
 * 等真的接入那个实现时再加，才有明确的测试覆盖它。
 */
@ConfigurationProperties(prefix = "oss")
public class OssProperties {

    /** 存储类型。目前实现：{@code local}（本地磁盘）。新增实现时在此登记取值。 */
    private String type = "local";

    /** 本地存储根目录。支持相对路径（相对进程工作目录）与绝对路径。 */
    private String localDir = "./upload/";

    /** 对外访问前缀（URL）。最终返回给调用方的地址 = urlPrefix + objectKey。 */
    private String urlPrefix = "/upload/";

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getLocalDir() {
        return localDir;
    }

    public void setLocalDir(String localDir) {
        this.localDir = localDir;
    }

    public String getUrlPrefix() {
        return urlPrefix;
    }

    public void setUrlPrefix(String urlPrefix) {
        this.urlPrefix = urlPrefix;
    }

    /**
     * 规范化后的访问前缀：保证以 {@code /} 开头、以 {@code /} 结尾。
     *
     * <p>为什么要在代码里兜这个而不是要求使用者写对：URL 拼接差一个斜杠就变成
     * {@code /shop/assetsproduct-img/x.png} 这种坏地址，而且报错现场离配置很远，
     * 排查成本远高于这里多几行判断。配置的「容错」应该由框架层吸收。
     */
    public String normalizedUrlPrefix() {
        String prefix = (urlPrefix == null || urlPrefix.isBlank()) ? "/" : urlPrefix.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix;
    }
}
