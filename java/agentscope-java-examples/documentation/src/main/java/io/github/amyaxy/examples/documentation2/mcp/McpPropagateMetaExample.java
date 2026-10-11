package io.github.amyaxy.examples.documentation2.mcp;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import java.time.Duration;
import java.util.List;

/**
 * McpPropagateMetaExample - MCP Server 全局（连接级）metadata 传递开关。
 *
 * <p>{@code propagateMeta} 控制框架是否把请求 metadata（RuntimeContext 中以
 * {@link io.agentscope.core.tool.mcp.McpMeta} 注册的用户条目，外加框架 tool-call id）
 * 作为 MCP tool call 请求的 {@code meta} 字段发给 server。默认 {@code true}；对不可信的
 * 第三方 MCP server 应设为 {@code false}，此时 {@code meta} 字段整体省略，任何 metadata
 * 都不出进程。
 *
 * <p><b>连接级 = Server 全局统一：</b>该开关挂在 {@link McpClientWrapper} 上，作用于这个
 * MCP server 下的<b>所有</b>工具——要么都传，要么都不传，不存在"这一半工具传、另一半不传"。
 * 它是每次调用时<b>实时读取</b>的（volatile），因此：
 * <ol>
 *   <li>连接级 {@code false} 是天花板：注册级 / 工具级覆盖即使写了 {@code true}，也只能在
 *       连接级放行之后生效（逻辑与，见 {@link McpPropagateMetaOverrideExample}）；</li>
 *   <li>运行中随时可 {@code wrapper.setPropagateMeta(...)} 急停 / 恢复，无需重新注册。</li>
 * </ol>
 *
 * <p><b>Configuration:</b>
 * <pre>
 *   export MCP_API_URL=https://your-mcp-gateway/mcp
 *   export MCP_API_KEY=optional_api_key
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   mvn -q exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.github.amyaxy.examples.documentation2.mcp.McpPropagateMetaExample
 * </pre>
 *
 * @author keep simple
 * @since 2026/9/29
 */
public class McpPropagateMetaExample {

    private static final String CLIENT_NAME = "remote-admin-mcp";

    /**
     * Runs the connection-level propagateMeta example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        banner("MCP PropagateMeta - Server-global (Connection) Level");

        String apiUrl = System.getenv("MCP_API_URL");
        String apiKey = System.getenv("MCP_API_KEY");
        if (apiUrl == null || apiUrl.isBlank()) {
            System.out.println("[SKIP] MCP_API_URL is not set — export it first, e.g.");
            System.out.println("       export MCP_API_URL=https://your-mcp-gateway/mcp");
            return;
        }

        // ── 1. 构建 MCP client：连接级统一开关 ──────────────────────────────────
        //
        // propagateMeta(false) 对本 server 注册出来的所有工具一刀切：请求里不再出现
        // meta 字段。不写则默认 true（框架同时发送 McpMeta 用户条目 + tool-call id）。
        McpClientWrapper client =
                McpClientBuilder.create(CLIENT_NAME)
                        .streamableHttpTransport(apiUrl)
                        .header("x-api-key", apiKey != null ? apiKey : "")
                        .timeout(Duration.ofSeconds(30))
                        .propagateMeta(false)
                        .buildSync();

        Toolkit toolkit = new Toolkit();
        toolkit.registerMcpClient(client).block();

        // ── 2. 验证：全局统一性 ────────────────────────────────────────────────
        printFlags(toolkit, client, "after register with propagateMeta(false)");

        // ── 3. 运行中急停 / 恢复：连接级开关是实时读取的 ─────────────────────────
        //
        // 无需 remove + re-register：一旦 setPropagateMeta(true)，本 server 全部工具
        // 立刻恢复传递（前提是没有更严格的注册级/工具级限制把它们压回去）。
        client.setPropagateMeta(true);
        printFlags(toolkit, client, "after live switch: setPropagateMeta(true)");

        client.setPropagateMeta(false);
        printFlags(toolkit, client, "after live switch: setPropagateMeta(false)");

        System.out.println("\nDone. Connection-level propagateMeta applies uniformly to every");
        System.out.println("tool of this MCP server, and is read live on every tool call.");
    }

    /** Prints the connection flag plus each registered tool's own (ANDed) flag. */
    private static void printFlags(Toolkit toolkit, McpClientWrapper client, String phase) {
        System.out.println("\n--- " + phase + " ---");
        System.out.println(
                "client '"
                        + CLIENT_NAME
                        + "'.propagateMeta = "
                        + client.isPropagateMeta()
                        + "   (ceiling, read live on every call)");
        List<String> names = toolkit.getToolNames().stream().sorted().toList();
        for (String name : names) {
            if (toolkit.getTool(name) instanceof McpTool mcpTool) {
                // 实际是否发出 meta = 工具级 && 连接级（McpTool#235 逻辑与）
                boolean effective = mcpTool.isPropagateMeta() && client.isPropagateMeta();
                System.out.println(
                        String.format(
                                "  tool '%s': toolFlag=%-5s && connection=%-5s => meta sent = %s",
                                name,
                                mcpTool.isPropagateMeta(),
                                client.isPropagateMeta(),
                                effective));
            }
        }
    }

    private static void banner(String title) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println(title);
        System.out.println("=".repeat(60));
    }
}
