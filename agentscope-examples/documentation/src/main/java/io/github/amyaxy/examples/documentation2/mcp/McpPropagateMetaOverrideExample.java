package io.github.amyaxy.examples.documentation2.mcp;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;

/**
 * McpPropagateMetaOverrideExample - 注册级 / 工具级 propagateMeta 覆盖。
 *
 * <p>连接级开关（见 {@link McpPropagateMetaExample}）只能对整个 MCP server 一刀切；
 * 当需要在<b>同一次注册</b>里做差异化时，走 {@code toolkit.registration()
 *         .mcpClient(client) ... .apply()}，它对应 {@code McpClientManager} 的完整重载：
 * <ul>
 *   <li><b>注册级默认</b>：{@code .propagateMeta(boolean)} —— 本次注册的所有工具统一的
 *       metadata 传递默认值；不调用则为 {@code true}；</li>
 *   <li><b>工具级条目</b>：{@code .propagateMeta(String toolName, boolean)} —— 针对单个
 *       工具的直接覆盖，<b>覆盖注册级默认</b>（不是"优先级大于"的叠加语义）：
 *       {@code true} 可以在注册级 {@code false} 时放宽单个工具，但多次调用按工具名累积、
 *       同名后写覆盖先写。</li>
 * </ul>
 *
 * <p><b>生效公式（注册时定格）：</b>
 * {@code 工具级条目 ?? 注册级默认 ?? true}，结果写进 {@link McpTool} 的
 * {@code propagateMeta} 标志；运行时实际是否发 meta 还要再与连接级开关取逻辑与
 * （{@code McpTool} 内 {@code toolFlag && wrapper.isPropagateMeta()}，连接级每次调用
 * 实时读取）。所以：
 * <pre>
 *   连接级 false  →  全 server 静默，注册级 / 工具级的 true 都放宽不了（天花板）
 *   连接级 true   →  注册级 false 默认全静默，工具级 true 单独放宽某个工具
 * </pre>
 *
 * <p><b>防呆：</b>{@code toolPropagateMetaOverrides} 里写了<b>未实际注册成功</b>的工具名
 * （拼错、远程改名、或被 {@code enableTools}/{@code disableTools} 过滤掉），注册直接抛
 * {@link IllegalArgumentException}——为"把 metadata 挡在不可信 server 之外"而设的静默
 * 覆盖绝不允许悄悄丢失。失败时 client 会被 best-effort 关闭，wrapper 不可复用。
 *
 * <p><b>Configuration:</b>
 * <pre>
 *   export MCP_API_URL=https://your-mcp-gateway/mcp
 *   export MCP_API_KEY=optional_api_key
 * </pre>
 *
 * @author keep simple
 * @since 2026/9/29
 */
public class McpPropagateMetaOverrideExample {

    /**
     * Runs the registration-level / per-tool propagateMeta override examples.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        banner("MCP PropagateMeta - Registration & Per-Tool Overrides");

        String apiUrl = requireEnv("MCP_API_URL");
        String apiKey = System.getenv("MCP_API_KEY");
        if (apiUrl == null) {
            return;
        }

        // ── 场景 1：连接级放行，注册级全静音 + 工具级单独放宽 ──────────────────
        //
        // 典型诉求：server 整体不可信（默认都不传 meta），只有一个自家工具需要
        // 继续收到 RuntimeContext 里 McpMeta 的回调地址。
        McpClientWrapper client =
                McpClientBuilder.create("partial-trust-mcp")
                        .streamableHttpTransport(apiUrl)
                        .header("x-api-key", apiKey != null ? apiKey : "")
                        .timeout(Duration.ofSeconds(30))
                        // 连接级默认 true（不写即默认），允许下面做差异化
                        .propagateMeta(true)
                        .buildSync();

        // buildSync() 只创建 wrapper，并不建立连接；initialize() 负责握手并缓存
        // 工具列表，幂等——随后 registerMcpClient() 再调它时直接返回。
        client.initialize().block();
        List<McpSchema.Tool> remoteTools = client.listTools().block();
        if (remoteTools == null || remoteTools.isEmpty()) {
            System.out.println("[SKIP] MCP server exposes no tools — nothing to demonstrate.");
            return;
        }
        String trusted = remoteTools.get(0).name();
        String untrusted = remoteTools.size() > 1 ? remoteTools.get(1).name() : "<need 2+ tools>";

        Toolkit toolkit = new Toolkit();
        toolkit.registration()
                .mcpClient(client)
                .propagateMeta(false) // 注册级默认：本 server 全部工具静默
                .propagateMeta(trusted, true) // 工具级条目：单独放宽这一个（覆盖注册级）
                .apply();

        System.out.println(
                "\n--- Scenario 1: connection=true, registration=false, "
                        + "per-tool '"
                        + trusted
                        + "'=true ---");
        printEffectiveFlags(toolkit, client);
        System.out.println("=> '" + trusted + "' 发 meta；其余工具（如 '" + untrusted + "'）注册级默认被压住，不发。");

        // ── 场景 2：连接级 false 是天花板，工具级 true 放宽不了 ─────────────────
        //
        // 同一个 Toolkit 重新注册一份：连接级直接关掉。
        McpClientWrapper strictClient =
                McpClientBuilder.create("locked-mcp")
                        .streamableHttpTransport(apiUrl)
                        .header("x-api-key", apiKey != null ? apiKey : "")
                        .timeout(Duration.ofSeconds(30))
                        .propagateMeta(false) // 连接级：全 server 统一关闭
                        .buildSync();

        Toolkit strictToolkit = new Toolkit();
        strictToolkit
                .registration()
                .mcpClient(strictClient)
                .propagateMeta(trusted, true) // 工具级想单独放宽 —— 无效
                .apply();

        System.out.println(
                "\n--- Scenario 2: connection=false + per-tool '" + trusted + "'=true ---");
        printEffectiveFlags(strictToolkit, strictClient);
        System.out.println("=> 工具级 true 在注册时被定格保留（重新打开连接级后它会继续生效），");
        System.out.println("   但连接级 false 期间，逻辑与结果恒为 false —— 永远发不出 meta。");

        // ── 场景 3：防呆 —— override 写了不存在的工具名，注册直接失败 ───────────
        System.out.println("\n--- Scenario 3: fail-loud on unknown tool name ---");
        McpClientWrapper typoClient =
                McpClientBuilder.create("typo-mcp")
                        .streamableHttpTransport(apiUrl)
                        .header("x-api-key", apiKey != null ? apiKey : "")
                        .timeout(Duration.ofSeconds(30))
                        .buildSync();

        Toolkit typoToolkit = new Toolkit();
        try {
            typoToolkit
                    .registration()
                    .mcpClient(typoClient)
                    .propagateMeta("no_such_tool_" + System.nanoTime(), false)
                    .apply();
            System.out.println("!! Unexpected: registration passed with an unknown tool name");
        } catch (IllegalArgumentException e) {
            System.out.println("Registration failed as designed: " + e.getMessage());
            System.out.println("=> 静默覆盖不会悄悄丢失；此时 typoClient 已被 best-effort " + "关闭，不可复用。");
        }

        System.out.println("\nDone.");
    }

    /** Prints per-tool flag, connection flag and the effective (AND) result. */
    private static void printEffectiveFlags(Toolkit toolkit, McpClientWrapper client) {
        toolkit.getToolNames().stream()
                .sorted()
                .forEach(
                        name -> {
                            if (toolkit.getTool(name) instanceof McpTool mcpTool) {
                                boolean toolFlag = mcpTool.isPropagateMeta();
                                boolean effective = toolFlag && client.isPropagateMeta();
                                System.out.println(
                                        String.format(
                                                "  tool '%s': toolFlag=%-5s && connection=%-5s"
                                                        + " => meta sent = %s",
                                                name,
                                                toolFlag,
                                                client.isPropagateMeta(),
                                                effective));
                            }
                        });
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            System.out.println("[SKIP] " + key + " is not set — export it first, e.g.");
            System.out.println("       export " + key + "=<value>");
            return null;
        }
        return value;
    }

    private static void banner(String title) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println(title);
        System.out.println("=".repeat(60));
    }
}
