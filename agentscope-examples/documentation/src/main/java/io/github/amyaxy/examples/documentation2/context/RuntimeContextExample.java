package io.github.amyaxy.examples.documentation2.context;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.github.amyaxy.examples.documentation2.utils.ModelUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Demonstrates how to thread per-call metadata through {@link RuntimeContext} and
 * combine it with persistent {@link AgentStateStore} storage so the same agent instance can
 * pick up history across runs.
 *
 * <p>{@link RuntimeContext} is a transient, per-call metadata bag — useful for
 * passing tenant ids, request ids, or anything else hooks and tools should see
 * during a single {@code call}. {@link AgentStateStore}
 * configured on the builder turn the same agent into one that loads and saves
 * conversation context to disk automatically.
 *
 * @author keep simaple
 * @since 2026/9/22
 */
public class RuntimeContextExample {

    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RuntimeContext Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Shows per-call RuntimeContext metadata and JsonFileAgentStateStore persistence.");
        System.out.println("=".repeat(60) + "\n");

        Path sessionPath =
                Paths.get(System.getProperty("user.home"), ".agentscope", "examples", "sessions");
        AgentStateStore stateStore = new JsonFileAgentStateStore(sessionPath);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt(
                                "You are a helpful assistant. Remember details from prior turns.")
                        .model(ModelUtils.buildOpenAIChatModel())
                        .toolkit(new Toolkit())
                        .stateStore(stateStore)
                        .defaultSessionId("runtime-context-demo")
                        .build();

        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("runtime-context-demo")
                        .put("request_id", "req-001")
                        .build();

        int loaded = agent.getAgentState(ctx).getContext().size();
        System.out.println("Loaded " + loaded + " message(s) from session.");

        Msg first = agent.call(List.of(new UserMessage("Hi, my name is Alice.")), ctx).block();
        System.out.println("Assistant: " + (first == null ? "" : first.getTextContent()));

        Msg second = agent.call(List.of(new UserMessage("What is my name?")), ctx).block();
        System.out.println("Assistant: " + (second == null ? "" : second.getTextContent()));

        System.out.println(
                "Final context size: "
                        + agent.getAgentState(ctx).getContext().size()
                        + " message(s).");
    }
}
