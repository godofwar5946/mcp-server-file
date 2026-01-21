package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpServerApplication {
    public static void main(String[] args) {
        ensureLogDirectory();
        SpringApplication.run(McpServerApplication.class, args);
    }

    /**
     * 提前创建日志目录（避免 logback 的 RollingFileAppender 因目录不存在而初始化失败）。
     * <p>
     * 规则与 logback-spring.xml 保持一致：优先读取系统属性/环境变量 LOG_PATH，默认使用 ./logs
     */
    private static void ensureLogDirectory() {
        String logPath = System.getProperty("LOG_PATH");
        if (logPath == null || logPath.isBlank()) {
            logPath = System.getenv("LOG_PATH");
        }
        if (logPath == null || logPath.isBlank()) {
            logPath = "logs";
        }
        try {
            Files.createDirectories(Path.of(logPath));
        } catch (Exception e) {
            // 此处不抛异常，避免影响应用启动；如需排查可临时改为 System.err 输出
        }
    }
}
