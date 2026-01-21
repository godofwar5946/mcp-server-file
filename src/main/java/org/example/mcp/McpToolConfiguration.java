package org.example.mcp;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * MCP 工具注册配置。
 * <p>
 * Spring AI MCP Server 会从 Spring 容器中收集 {@link ToolCallback}，
 * 并通过 MCP 协议将这些工具能力暴露给调用方（例如 Codex、Claude Desktop、Cursor 等）。
 */
@Configuration
public class McpToolConfiguration {

    @Bean
    public List<ToolCallback> fileToolCallbacks(FileMcpTools tools) {
        return Arrays.asList(ToolCallbacks.from(tools));
    }
}
