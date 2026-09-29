package io.github.amyaxy.examples.documentation2.utils;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.Objects;

/**
 * ModelUtils
 *
 * @author keep simaple
 * @since 2026/9/21
 */
public class ModelUtils {

    public static Model buildOpenAIChatModel() {

        String openaiApiKey = System.getenv("OPENAI_API_KEY");
        String openaiModelName = System.getenv("OPENAI_MODEL_NAME");
        String openaiStream = System.getenv("OPENAI_STREAM");

        String openaiBaseUrl = System.getenv("OPENAI_BASE_URL");
        String openaiEndpointPath = System.getenv("OPENAI_ENDPOINT_PATH");

        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(openaiApiKey).modelName(openaiModelName).stream(
                        Boolean.parseBoolean(openaiStream));

        if (Objects.nonNull(openaiBaseUrl)) {
            builder.baseUrl(openaiBaseUrl);
        }
        if (Objects.nonNull(openaiEndpointPath)) {
            builder.endpointPath(openaiEndpointPath);
        }
        return builder.build();
    }
}
