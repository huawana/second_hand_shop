package com.example.oss;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储抽象 —— starter 对外的唯一契约。
 *
 * <p>设计要点：接口只暴露「存」与「删」两个动作，且都以 <b>URL</b> 为交互单位
 * （不是文件路径）。原因：
 * <ul>
 *   <li>业务代码要的是「能存到数据库、能渲染进 img src 的地址」，
 *       至于文件到底躺在磁盘还是对象存储里，业务方不需要也不应该知道；</li>
 *   <li>{@link #delete(String)} 接收 URL 而不是路径，才能让不同实现各自解析 ——
 *       本地实现按前缀截取相对路径，云实现按 objectKey 调 SDK 删除，
 *       业务代码两种情况下都是同一行 {@code storage.delete(oldUrl)}。</li>
 * </ul>
 */
public interface FileStorage {

    /**
     * 保存文件。
     *
     * @param file      上传的文件（不能为空）
     * @param objectKey 对象键，通常是「文件名」或「日期/文件名」；实现方必须对其做安全性校验
     * @return 可对外访问的 URL（= 配置的 urlPrefix + objectKey）
     */
    String store(MultipartFile file, String objectKey);

    /**
     * 按 URL 删除文件（用于「换图后清理旧图」这类场景）。
     *
     * @return 真正删掉了返回 true；文件不存在、或该 URL 不属于本存储时返回 false（不抛异常）
     */
    boolean delete(String publicUrl);
}
