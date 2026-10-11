package io.github.amyaxy.examples.documentation2.middleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import io.github.amyaxy.examples.documentation2.utils.ModelUtils;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * SystemPromptMiddlewareExample - Demonstrates injecting dynamic content into the system prompt
 * by overriding {@link MiddlewareBase#onSystemPrompt(Agent, RuntimeContext, String)}.
 *
 * <p>{@code onSystemPrompt} is called once per {@code agent.call()} before any messages are
 * sent to the model. Each middleware in the chain receives the output of the previous one, so
 * multiple middlewares can layer information into the final system prompt.
 *
 * <p><b>Common use-cases:</b>
 * <ul>
 *   <li>Injecting the current date/time for time-aware reasoning</li>
 *   <li>Adding user-specific settings (locale, role, preferences)</li>
 *   <li>Appending environment-specific instructions (staging vs. production)</li>
 * </ul>
 *
 * @author keep simaple
 * @since 2026/9/22
 */
public class SystemPromptMiddlewareExample {

    /**
     * Runs the system prompt middleware example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) throws java.io.IOException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("System Prompt Middleware Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates onSystemPrompt() to inject timestamp and environment info.\n"
                        + "Ask 'What time is it?' — the agent knows the current UTC time\n"
                        + "because the middleware injects it on every call.");
        System.out.println("=".repeat(60) + "\n");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("ContextAwareAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(ModelUtils.buildOpenAIChatModel())
                        .middleware(new TimestampMiddleware())
                        .middleware(new EnvironmentMiddleware("demo", "user-42"))
                        .middleware(new AgentTraceMiddleware())
                        .build();

        System.out.println("Try: 'What time is it?' or 'Who am I?'\n");

        Msg response =
                agent.call(new UserMessage("user", "What time is it and what environment am I in?"))
                        .block();
        System.out.println("Agent: " + (response != null ? response.getTextContent() : "(null)"));

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Chat started. Type 'exit' to quit.\n");

        while (true) {
            System.out.print("You: ");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }
            if (input.isBlank()) {
                continue;
            }
            Msg userMsg = new UserMessage(input.trim());
            System.out.print("\nAgent: ");
            agent.streamEvents(userMsg)
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    System.out.print(e.getDelta());
                                }
                            })
                    .blockLast();
            System.out.println("\n");
        }
    }

    /**
     * Middleware that appends the current UTC timestamp to the system prompt.
     *
     * <p>The agent can reference this to answer time-related questions without a
     * clock tool.
     */
    public static class TimestampMiddleware implements MiddlewareBase {

        /**
         * Appends the current UTC timestamp to the system prompt.
         *
         * @param agent         the calling agent (available for context inspection)
         * @param currentPrompt the system prompt as built so far (output of any prior middleware)
         * @return updated system prompt with timestamp appended
         */
        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
            String timestamp = Instant.now().toString();
            String appended = currentPrompt + "\n\n[Context] Current UTC time: " + timestamp;
            return Mono.just(appended);
        }
    }

    /**
     * Middleware that appends environment and user information to the system prompt.
     */
    public static class EnvironmentMiddleware implements MiddlewareBase {

        private final String environment;
        private final String userId;

        /**
         * Constructs the environment middleware.
         *
         * @param environment deployment environment label (e.g. "demo", "production")
         * @param userId      current user identifier
         */
        public EnvironmentMiddleware(String environment, String userId) {
            this.environment = environment;
            this.userId = userId;
        }

        /**
         * Appends environment and user information to the system prompt.
         *
         * @param agent         the calling agent
         * @param currentPrompt the system prompt as built by prior middlewares
         * @return updated system prompt with environment context appended
         */
        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
            String appended =
                    currentPrompt
                            + "\n[Context] Environment: "
                            + environment
                            + " | User ID: "
                            + userId;
            return Mono.just(appended);
        }
    }
}
