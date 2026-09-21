package io.github.amyaxy.examples.documentation2.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.github.amyaxy.examples.documentation2.utils.ModelUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;


public class BasicChatExample {

    public static void main(String[] args) throws Exception {

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Basic Chat Example");
        System.out.println("=".repeat(60));
        System.out.println("A simple interactive chat with streaming output.");
        System.out.println("Type 'exit' to quit.\n");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt("You are a helpful AI assistant. Be friendly and concise.")
                        .model(ModelUtils.buildOpenAIChatModel())
                        .toolkit(new Toolkit())
                        .build();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

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

            System.out.print("\nAssistant: ");
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
}