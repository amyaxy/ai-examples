package io.github.amyaxy.examples.documentation2.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import io.github.amyaxy.examples.documentation2.utils.ModelUtils;
import java.util.List;

/**
 * ToolExecutionContextExample - Demonstrates passing per-call context to tools via
 * {@link RuntimeContext}.
 *
 * <p>{@code RuntimeContext} allows the caller to pass arbitrary, type-safe objects to tools for
 * a single agent call. Common use-cases: tenant/user metadata, database connections, audit loggers.
 *
 * <p><b>How automatic POJO injection works:</b>
 * <ol>
 *   <li>Build a {@code RuntimeContext} with one or more typed values:
 *       {@code RuntimeContext.builder().put(UserContext.class, ctx).build()}</li>
 *   <li>Pass it to {@code agent.call(msgs, runtimeContext)} (or {@code agent.stream}).</li>
 *   <li>In a {@code @Tool}-annotated method, declare a parameter that is NOT annotated with
 *       {@code @ToolParam} and whose type was registered in the context — the framework injects
 *       the value automatically without the model needing to supply it.</li>
 * </ol>
 *
 * @author keep simaple
 * @since 2026/9/22
 */
public class ToolExecutionContextExample {

    /**
     * Runs the RuntimeContext injection example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RuntimeContext (Tool Execution Context) Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates per-call context injection via RuntimeContext.\n"
                        + "The UserContext POJO is injected automatically into @Tool methods\n"
                        + "without the model supplying it as a parameter.");
        System.out.println("=".repeat(60) + "\n");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PersonalizedTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("PersonalizedAgent")
                        .sysPrompt(
                                "You are a personalized assistant. "
                                        + "Use the greet and preferences tools.")
                        .model(ModelUtils.buildOpenAIChatModel())
                        .toolkit(toolkit)
                        .middleware(new AgentTraceMiddleware())
                        .build();

        // ── Scenario A: Alice calls with full preferences ─────────────────────────────
        UserContext aliceCtx = new UserContext("alice", "en", List.of("dark mode", "compact view"));
        RuntimeContext aliceRunCtx =
                RuntimeContext.builder()
                        .put(UserContext.class, aliceCtx) // type-safe singleton slot
                        .userId("alice")
                        .sessionId("session-alice")
                        .build();

        System.out.println("--- Alice's call ---");
        Msg aliceResponse =
                agent.call(
                                List.of(
                                        new UserMessage(
                                                "user", "Greet me and list my preferences.")),
                                aliceRunCtx)
                        .block();
        System.out.println(
                "Agent: " + (aliceResponse != null ? aliceResponse.getTextContent() : "(null)"));

        // ── Scenario B: Bob calls with different preferences ──────────────────────────
        UserContext bobCtx = new UserContext("bob", "zh", List.of("large fonts", "high contrast"));
        RuntimeContext bobRunCtx =
                RuntimeContext.builder()
                        .put(UserContext.class, bobCtx)
                        .userId("bob")
                        .sessionId("session-bob")
                        .build();

        System.out.println("\n--- Bob's call ---");
        Msg bobResponse =
                agent.call(List.of(new UserMessage("user", "What are my preferences?")), bobRunCtx)
                        .block();
        System.out.println(
                "Agent: " + (bobResponse != null ? bobResponse.getTextContent() : "(null)"));
    }

    /**
     * Per-call user context passed via RuntimeContext.
     *
     * <p>Instances of this class are injected automatically into {@code @Tool} methods
     * that declare it as a non-{@code @ToolParam} parameter.
     */
    public record UserContext(String username, String locale, List<String> preferences) {}

    /** Tools that receive {@link UserContext} via automatic POJO injection. */
    public static class PersonalizedTools {

        /**
         * Greets the user by name using the injected {@link UserContext}.
         *
         * <p>The {@code userCtx} parameter has no {@code @ToolParam} annotation — the framework
         * resolves it from the {@link RuntimeContext} registered with
         * {@code put(UserContext.class, value)}.
         *
         * @param greeting  greeting word supplied by the model
         * @param userCtx   injected from RuntimeContext — NOT passed by the model
         * @return personalised greeting string
         */
        @Tool(name = "greet", description = "Greet the user with a custom greeting")
        public String greet(
                @ToolParam(name = "greeting", description = "Greeting word, e.g. 'Hello'")
                        String greeting,
                UserContext userCtx) {
            String name = userCtx != null ? userCtx.username() : "unknown";
            return greeting + ", " + name + "!";
        }

        /**
         * Returns the current user's display preferences from the injected {@link UserContext}.
         *
         * @param userCtx injected from RuntimeContext
         * @return comma-separated preference list
         */
        @Tool(name = "get_preferences", description = "Get the current user's display preferences")
        public String getPreferences(UserContext userCtx) {
            if (userCtx == null) {
                return "No user context available";
            }
            return "User: "
                    + userCtx.username()
                    + " | Locale: "
                    + userCtx.locale()
                    + " | Preferences: "
                    + String.join(", ", userCtx.preferences());
        }
    }
}
