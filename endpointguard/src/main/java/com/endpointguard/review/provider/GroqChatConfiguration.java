package com.endpointguard.review.provider;

import com.endpointguard.common.config.AppProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.ApiKey;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Configuration
public class GroqChatConfiguration {

    @Bean
    public ChatClient groqChatClient(AppProperties appProperties) {
        AppProperties.Llm.Groq groq = appProperties.getLlm().getGroq();
        String apiKey = groq.getApiKey();
        ApiKey springAiApiKey = apiKey == null || apiKey.isBlank()
                ? new NoopApiKey()
                : new SimpleApiKey(apiKey);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(groq.getBaseUrl())
                .completionsPath("/openai/v1/chat/completions")
                .apiKey(springAiApiKey)
                .responseErrorHandler(groqResponseErrorHandler())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(groq.getModel()).build())
                .retryTemplate(groqRetryTemplate(appProperties))
                .build();
        return ChatClient.builder(chatModel).build();
    }

    RetryTemplate groqRetryTemplate(AppProperties appProperties) {
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                Math.max(1, Math.min(3, appProperties.getLlm().getMaxRetries() + 1)),
                Map.of(
                    TransientAiException.class, true,
                    RetryableRequestTimeoutException.class, true,
                        HttpClientErrorException.TooManyRequests.class, true,
                        HttpServerErrorException.class, true,
                        ResourceAccessException.class, true),
                true);
        ExponentialRandomBackOffPolicy backOffPolicy = new ExponentialRandomBackOffPolicy();
        backOffPolicy.setInitialInterval(100);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(300);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        return retryTemplate;
    }

    private ResponseErrorHandler groqResponseErrorHandler() {
        return new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return response.getStatusCode().isError();
            }

            @Override
            public void handleError(ClientHttpResponse response) throws IOException {
                var status = response.getStatusCode();
                var headers = response.getHeaders();
                byte[] body = StreamUtils.copyToByteArray(response.getBody());
                if (status.is4xxClientError()) {
                    if (status.value() == 408) {
                        throw new RetryableRequestTimeoutException(
                                status, response.getStatusText(), headers, body);
                    }
                    throw HttpClientErrorException.create(
                            status, response.getStatusText(), headers, body, StandardCharsets.UTF_8);
                }
                throw HttpServerErrorException.create(
                        status, response.getStatusText(), headers, body, StandardCharsets.UTF_8);
            }
        };
    }

    static final class RetryableRequestTimeoutException extends HttpClientErrorException {
        RetryableRequestTimeoutException(
                HttpStatusCode status, String statusText, org.springframework.http.HttpHeaders headers, byte[] body) {
            super(status, statusText, headers, body, StandardCharsets.UTF_8);
        }
    }
}
