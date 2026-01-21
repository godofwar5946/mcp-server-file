package org.example.filesystem;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件系统 MCP 服务的 Bean 装配。
 * <p>
 * 说明：
 * <ul>
 *   <li>把配置 {@link FileServerProperties} 注入到安全路径解析器与待写入存储中。</li>
 *   <li>这里不引入任何数据库/外部依赖，全部基于本地文件系统。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class FileServerConfiguration {

    @Bean
    public SecurePathResolver securePathResolver(FileServerProperties properties) {
        return new SecurePathResolver(properties);
    }

    @Bean
    public PendingFileWriteStore pendingFileWriteStore(FileServerProperties properties) {
        return new PendingFileWriteStore(
                properties.getPendingWriteTtl(),
                properties.getPendingWriteMaxBytes().toBytes()
        );
    }

    @Bean
    public FileIndexCache fileIndexCache(FileServerProperties properties) {
        return new FileIndexCache(properties);
    }
}
