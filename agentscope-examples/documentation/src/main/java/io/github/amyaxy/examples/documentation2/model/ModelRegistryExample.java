package io.github.amyaxy.examples.documentation2.model;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ModelRegistry;
import io.github.amyaxy.examples.documentation2.utils.ModelUtils;

/**
 * 备注
 *
 * @author keep simple
 * @since 2026/9/21 22:34
 */
public class ModelRegistryExample {

    /**
     * Runs the model-string resolution demonstration.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("ModelRegistry Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Shows how to specify a model with a plain string — no manual construction"
                        + " needed.");
        System.out.println("=".repeat(60) + "\n");

        ModelRegistry.register("openai:qwen3.7-flash-2026-07-15", ModelUtils.buildOpenAIChatModel());

        // ── 1. Check which providers are available ────────────────────────────────────
        //
        // ModelRegistry.canResolve() probes the registry without actually creating a model.
        // Use it at startup to give users an early, clear error message.
        System.out.println(
                "Available provider extensions (requires corresponding module and env var):");
        System.out.println(
                "  openai:qwen3.7-flash-2026-07-15  → " + ModelRegistry.canResolve("openai:qwen3.7-flash-2026-07-15"));
        System.out.println();

        // ── 2. Create an agent with just a model-ID string ────────────────────────────
        //
        // ReActAgent.Builder.model(String) calls ModelRegistry.resolve() internally.
        // The framework reads DASHSCOPE_API_KEY from the environment and wires up the
        // DashScope model — no manual DashScopeChatModel.builder() call required.
        System.out.println("Building agent with model string \"qwen3.7-flash-2026-07-15\" ...");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ModelStringDemo")
                        .sysPrompt("You are a concise assistant. Reply in one sentence.")
                        .model("openai:qwen3.7-flash-2026-07-15") // ← the only thing needed to configure the
                        // model
                        .build();

        Msg response = agent.call(new UserMessage("user", "What is 2 + 2?")).block();
        System.out.println("Agent: " + (response != null ? response.getTextContent() : "(null)"));
        System.out.println();

        // ── 3. Switch provider by changing the string ─────────────────────────────────
        //
        // The provider prefix selects which backend to use.  Swapping providers only
        // requires updating the model-ID string — no other code changes.
        //
        // Uncomment one of these lines to try a different provider:
        //
        //   .model("openai:gpt-4o")              // requires OPENAI_API_KEY
        //   .model("dashscope:qwen-max")          // explicit DashScope prefix
        //   .model("anthropic:claude-opus-4-5")   // requires ANTHROPIC_API_KEY
        //   .model("gemini:gemini-2.0-flash")     // requires GEMINI_API_KEY
        //   .model("ollama:llama3")               // requires local Ollama instance
        System.out.println(
                "To switch providers, change the model-ID string — no other code changes needed.");
    }
}