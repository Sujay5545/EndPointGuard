package com.endpointguard.github;

import com.endpointguard.common.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GithubRestApiClientTests {

    @Test
    void validatesRepositoryWithBearerTokenAndGithubHeaders() throws Exception {
        try (MockGitHubServer server = new MockGitHubServer()) {
            server.enqueue("/repos/acme/payments-api", 200,
                    "{\"full_name\":\"acme/payments-api\",\"private\":false,\"description\":\"Payments API\",\"default_branch\":\"main\"}");

            AppProperties props = new AppProperties();
            props.getGithub().setToken("test-token");
            props.getGithub().setApiBaseUrl(server.baseUrl());
            GithubRestApiClient client = new GithubRestApiClient(new org.springframework.web.client.RestTemplate(), props, new ObjectMapper());

            GithubRepositoryMetadata metadata = client.validateRepository("acme/payments-api");

            assertThat(metadata.fullName()).isEqualTo("acme/payments-api");
            assertThat(metadata.isPrivate()).isFalse();
            assertThat(metadata.description()).isEqualTo("Payments API");
        }
    }

    @Test
    void retrievesPullRequestMetadata() throws Exception {
        try (MockGitHubServer server = new MockGitHubServer()) {
            server.enqueue("/repos/acme/payments-api/pulls/42", 200,
                    "{\"number\":42,\"title\":\"Add payout endpoint\",\"state\":\"open\",\"html_url\":\"https://github.com/acme/payments-api/pull/42\",\"user\":{\"login\":\"alice\"}} ");

            AppProperties props = new AppProperties();
            props.getGithub().setToken("test-token");
            props.getGithub().setApiBaseUrl(server.baseUrl());
            GithubRestApiClient client = new GithubRestApiClient(new org.springframework.web.client.RestTemplate(), props, new ObjectMapper());

            GithubPullRequestMetadata pr = client.getPullRequest("acme/payments-api", 42);

            assertThat(pr.number()).isEqualTo(42);
            assertThat(pr.title()).isEqualTo("Add payout endpoint");
            assertThat(pr.author()).isEqualTo("alice");
            assertThat(pr.htmlUrl()).isEqualTo("https://github.com/acme/payments-api/pull/42");
        }
    }

    @Test
    void retrievesPullRequestFilesAcrossNextLinkPages() throws Exception {
        try (MockGitHubServer server = new MockGitHubServer()) {
            server.enqueue("/repos/acme/payments-api/pulls/42/files", 200,
                    "[{\"filename\":\"src/main/java/com/acme/PaymentController.java\",\"additions\":12,\"deletions\":2}]",
                    "<" + server.baseUrl() + "/repos/acme/payments-api/pulls/42/files?page=2>; rel=\"next\"");
            server.enqueue("/repos/acme/payments-api/pulls/42/files?page=2", 200,
                    "[{\"filename\":\"src/main/resources/application.yml\",\"additions\":4,\"deletions\":1}]");

            AppProperties props = new AppProperties();
            props.getGithub().setToken("test-token");
            props.getGithub().setApiBaseUrl(server.baseUrl());
            GithubRestApiClient client = new GithubRestApiClient(new org.springframework.web.client.RestTemplate(), props, new ObjectMapper());

            var files = client.getPullRequestFiles("acme/payments-api", 42);

            assertThat(files).hasSize(2);
            assertThat(files).extracting("filename").contains("src/main/java/com/acme/PaymentController.java", "src/main/resources/application.yml");
        }
    }

    @Test
    void retriesOneTransientServerFailure() throws Exception {
        try (MockGitHubServer server = new MockGitHubServer()) {
            server.enqueue("/repos/acme/payments-api", 500, "{\"message\":\"server error\"}");
            server.enqueue("/repos/acme/payments-api", 200, "{\"full_name\":\"acme/payments-api\",\"private\":false,\"description\":\"Payments API\"}");

            AppProperties props = new AppProperties();
            props.getGithub().setToken("test-token");
            props.getGithub().setApiBaseUrl(server.baseUrl());
            GithubRestApiClient client = new GithubRestApiClient(new org.springframework.web.client.RestTemplate(), props, new ObjectMapper());

            GithubRepositoryMetadata metadata = client.validateRepository("acme/payments-api");

            assertThat(metadata.fullName()).isEqualTo("acme/payments-api");
        }
    }

    @Test
    void exposesRateLimitResetWithoutSleepingOrRetrying() throws Exception {
        try (MockGitHubServer server = new MockGitHubServer()) {
            server.enqueue("/repos/acme/payments-api", 403, "{\"message\":\"API rate limit exceeded\"}", null, "1700000000", "0");

            AppProperties props = new AppProperties();
            props.getGithub().setToken("test-token");
            props.getGithub().setApiBaseUrl(server.baseUrl());
            GithubRestApiClient client = new GithubRestApiClient(new org.springframework.web.client.RestTemplate(), props, new ObjectMapper());

            try {
                client.validateRepository("acme/payments-api");
            } catch (GithubApiException ignored) {
                // expected rate-limit failure
            }

            assertThat(client.getRateLimitReset()).isEqualTo(Instant.ofEpochSecond(1700000000L));
        }
    }

    private static final class MockGitHubServer implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, Deque<ResponseSpec>> responses = new HashMap<>();

        private MockGitHubServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", exchange -> {
                String requestPath = exchange.getRequestURI().getPath();
                String query = exchange.getRequestURI().getQuery();
                String lookupKey = query == null ? requestPath : requestPath + "?" + query;
                Deque<ResponseSpec> queue = responses.getOrDefault(lookupKey, responses.getOrDefault(requestPath, new ArrayDeque<>()));
                if (queue == null || queue.isEmpty()) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(404, 0);
                    exchange.getResponseBody().close();
                    return;
                }
                ResponseSpec responseSpec = queue.removeFirst();
                Headers headers = exchange.getResponseHeaders();
                headers.add("Content-Type", "application/json");
                if (responseSpec.linkHeader != null) {
                    headers.add("Link", responseSpec.linkHeader);
                }
                if (responseSpec.resetHeader != null) {
                    headers.add("X-RateLimit-Reset", responseSpec.resetHeader);
                }
                if (responseSpec.remainingHeader != null) {
                    headers.add("X-RateLimit-Remaining", responseSpec.remainingHeader);
                }
                byte[] body = responseSpec.body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseSpec.status, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
        }

        public String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        public void enqueue(String path, int status, String body) {
            enqueue(path, status, body, null, null, null);
        }

        public void enqueue(String path, int status, String body, String linkHeader) {
            enqueue(path, status, body, linkHeader, null, null);
        }

        public void enqueue(String path, int status, String body, String linkHeader, String resetHeader, String remainingHeader) {
            responses.computeIfAbsent(path, ignored -> new ArrayDeque<>()).addLast(new ResponseSpec(status, body, linkHeader, resetHeader, remainingHeader));
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private record ResponseSpec(int status, String body, String linkHeader, String resetHeader, String remainingHeader) {}
    }
}
