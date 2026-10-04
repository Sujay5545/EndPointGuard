package com.endpointguard.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Github github = new Github();
    private Http http = new Http();
    private Llm llm = new Llm();
    private Risk risk = new Risk();
    private Monitoring monitoring = new Monitoring();
    private Metrics metrics = new Metrics();
    private Cors cors = new Cors();

    @Data
    public static class Jwt {
        private String secret;
        private long expirationMs = 86400000;
    }

    @Data
    public static class Github {
        private String token;
        private String webhookSecret;
        private boolean requireWebhookSignature = true;
        private String apiBaseUrl = "https://api.github.com";
    }

    @Data
    public static class Http {
        private int sharedTimeoutSeconds = 5;
    }

    @Data
    public static class Llm {
        private String provider = "groq";
        private OpenAi openai = new OpenAi();
        private Anthropic anthropic = new Anthropic();
        private Gemini gemini = new Gemini();
        private Groq groq = new Groq();
        private Ollama ollama = new Ollama();
        private int timeoutSeconds = 30;
        private int maxRetries = 1;

        @Data public static class OpenAi { private String apiKey; }
        @Data public static class Anthropic { private String apiKey; }
        @Data public static class Groq {
            private String apiKey;
            private String baseUrl = "https://api.groq.com";
            private String model = "openai/gpt-oss-20b";
        }
        @Data public static class Gemini {
            private String apiKey;
            private String model = "gemini-3.8-flash";
        }
        @Data public static class Ollama { private String baseUrl = "http://localhost:11434"; }
    }

    @Data
    public static class Risk {
        private Thresholds thresholds = new Thresholds();
        private Traffic traffic = new Traffic();

        @Data
        public static class Thresholds {
            private double medium = 0.6;
            private double high = 0.8;
        }

        @Data
        public static class Traffic {
            private double requestThreshold = 1000;
            private double diffSizeThreshold = 500;
            private double incidentCountThreshold = 5;
        }
    }

    @Data
    public static class Monitoring {
        private int windowHours = 2;
        private int checkIntervalMinutes = 15;
        private long checkIntervalMs = 900000;
        private int minSampleCount = 10;
    }

    @Data
    public static class Metrics {
        private long pollIntervalMs = 60000;
        private String demoApiBaseUrl = "http://localhost:8081";
    }

    @Data
    public static class Cors {
        private String allowedOrigins = "http://localhost:5173";
    }
}
