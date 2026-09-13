package shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
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

