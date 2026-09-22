package io.github.amyaxy.examples.documentation2.streaming;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.github.amyaxy.examples.documentation2.utils.ModelUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * StreamingWebExample - Spring Boot + SSE streaming agent responses.
 *
 * <p>Migration notes (from documentation/quickstart):
 * <ul>
 *   <li>Replaced {@code legacy.session.JsonFileAgentStateStore} with {@code io.agentscope.core.state.JsonFileAgentStateStore}.</li>
 *   <li>Replaced {@code agent.loadIfExists(session, id)} / {@code agent.saveTo(session, id)}
 *       with {@code .stateStore(session).defaultSessionId(sessionId)} on the builder.</li>
 * </ul>
 *   curl -N "http://localhost:8080/chat?message=Hello"
 *   curl -N "http://localhost:8080/chat?message=What+is+AI?&amp;sessionId=my-session"
 * @author keep simaple
 * @since 2026/9/22
 */
@SpringBootApplication
public class StreamingWebExample {

    /**
     * Starts the Spring Boot application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(StreamingWebExample.class, args);
    }

    /** REST controller providing the streaming chat and health endpoints. */
    @RestController
    public static class ChatController implements InitializingBean {

        private Path sessionPath;

        @Override
        public void afterPropertiesSet() {

            sessionPath =
                    Paths.get(
                            System.getProperty("user.home"),
                            ".agentscope",
                            "examples",
                            "web-sessions");

            System.out.println("\n=== StreamingWeb Example Started ===");
            System.out.println("Server running at: http://localhost:8080");
            System.out.println("\nTry:");
            System.out.println("  curl -N \"http://localhost:8080/chat?message=Hello\"");
            System.out.println(
                    "  curl -N"
                            + " \"http://localhost:8080/chat?message=What+is+AI&sessionId=my-session\"");
            System.out.println("\nPress Ctrl+C to stop.\n");
        }

        /**
         * Chat endpoint that streams agent responses via SSE.
         *
         * @param message   user message
         * @param sessionId session identifier (defaults to {@code "default"})
         * @return flux of incremental text chunks
         */
        @GetMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public Flux<AgentEvent> chat(
                @RequestParam String message,
                @RequestParam(defaultValue = "default") String sessionId) {

            AgentStateStore stateStore = new JsonFileAgentStateStore(sessionPath);

            // IMPORTANT: Create a new agent per request. ReActAgent is NOT thread-safe — a single
            // instance rejects concurrent call()s. Model and AgentStateStore are safe to share;
            // Toolkit is deep-copied inside build(). This is the recommended pattern for web apps.
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("WebAgent")
                            .model(ModelUtils.buildOpenAIChatModel())
                            .stateStore(stateStore)
                            .defaultSessionId(sessionId)
                            .build();

            Msg userMsg = new UserMessage(message);

            return agent.streamEvents(userMsg)
                    .subscribeOn(Schedulers.boundedElastic());
        }

        /**
         * Health check endpoint.
         *
         * @return {@code "OK"}
         */
        @GetMapping("/health")
        public String health() {
            return "OK";
        }
    }
}
