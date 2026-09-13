package shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 【Phase 1】排除 {@link UserDetailsServiceAutoConfiguration}：
 * 只要项目里没有自定义 UserDetailsService，Spring Boot 就会自动创建一个内存用户
 * {@code user} + 随机密码，并在启动日志里打印 "Using generated security password: ..."。
 * 本项目认证完全自研（Controller 校验 + 签发 JWT），既不需要这个默认用户，
 * 也不应该让它存在 —— 生产环境里一个意料之外的可登录账号本身就是安全隐患。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        // 【Bug 修复】原为 SpringApplication.run(Application.class) —— args 被丢弃，
        // 导致所有命令行参数静默失效：--server.port=8081、--spring.profiles.active=prod、
        // --spring.datasource.url=... 全部不起作用，只能改配置文件。
        // 这直接破坏了「配置外置」能力（容器编排、多环境部署都依赖它）。
        SpringApplication.run(Application.class, args);
    }
}

