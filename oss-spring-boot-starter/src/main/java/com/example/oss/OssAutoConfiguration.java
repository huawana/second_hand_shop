package com.example.oss;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 文件存储自动配置。
 *
 * <p>这是 starter 的核心：消费方只要把 jar 放进依赖，<b>一行代码都不用写</b>
 * 就能注入 {@link FileStorage}。三个关键点：
 *
 * <ol>
 *   <li><b>{@code @AutoConfiguration} + {@code AutoConfiguration.imports}</b>：
 *       Spring Boot 3 的自动配置注册方式。文件位于
 *       {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}，
 *       里面只写本类的全限定名。
 *       注意与 Boot 2 的 {@code META-INF/spring.factories} 的区别 ——
 *       Boot 2.7 起 {@code spring.factories} 里的 EnableAutoConfiguration 已被废弃，
 *       Boot 3 完全改用 {@code .imports} 文件（面试常考的版本差异）。</li>
 *
 *   <li><b>{@code @ConditionalOnProperty}</b>：用配置决定「装哪个实现」。
 *       在本类上声明，意味着 {@code oss.type} 不是 {@code local}（或未配置时用默认值）
 *       时整套自动配置都不生效 —— 这样消费方可以彻底关掉它、自己提供 FileStorage。</li>
 *
 *   <li><b>{@code @ConditionalOnMissingBean}</b>：<i>自动配置必须让位给用户配置</i>。
 *       消费方只要自己声明了一个 {@code FileStorage} Bean，这里就不再创建 ——
 *       这是「约定优于配置」能被接受的前提（框架不夺权），
 *       也是所有官方 starter 都遵守的规则。</li>
 * </ol>
 *
 * <p><b>新增一种存储实现的做法</b>（例如 MinIO）：
 * <pre>{@code
 * @Bean
 * @ConditionalOnProperty(prefix = "oss", name = "type", havingValue = "minio")
 * @ConditionalOnMissingBean(FileStorage.class)
 * public FileStorage minioFileStorage(OssProperties p) { return new MinioFileStorage(p); }
 * }</pre>
 * 本类上的条件需要相应放宽（去掉 havingValue），否则 {@code oss.type=minio} 时整类不生效。
 * 当前项目只落地了 local 实现，所以条件写在类上更直观；
 * 这也是「不预写没有被测试覆盖的实现」的取舍 —— 真要接 MinIO 时再补实现和它的测试。
 */
@AutoConfiguration
@EnableConfigurationProperties(OssProperties.class)
@ConditionalOnProperty(prefix = "oss", name = "type", havingValue = "local", matchIfMissing = true)
public class OssAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FileStorage.class)
    public FileStorage fileStorage(OssProperties properties) {
        return new LocalFileStorage(properties);
    }
}
