package com.endpointguard.github;

import com.endpointguard.common.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class GithubRestApiClient {

    private static final Pattern NEXT_LINK_PATTERN = Pattern.compile("<([^>]+)>; rel=\"next\"");
    private static final int MAX_RETRIES = 2;

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private volatile Instant lastRateLimitReset = Instant.EPOCH;

    public boolean isConfigured() {
        return appProperties.getGithub() != null
                && appProperties.getGithub().getToken() != null
                && !appProperties.getGithub().getToken().isBlank();
    }

    public String normalizeRepositoryName(String repositoryFullName) {
        String value = repositoryFullName == null ? "" : repositoryFullName.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("GitHub repository name is required");
        }
        if (value.startsWith("https://github.com/")) {
            value = value.substring("https://github.com/".length());
        }
        if (value.startsWith("git@github.com:")) {
            value = value.substring("git@github.com:".length());
        }
        if (value.contains("/")) {
            String[] parts = value.split("/");
            if (parts.length == 2) {
                return parts[0].trim() + "/" + parts[1].trim();
            }
        }
        throw new IllegalArgumentException("Repository must be in owner/name format");
    }

    public GithubRepositoryMetadata validateRepository(String repoFullName) {
        String canonical = normalizeRepositoryName(repoFullName);
        JsonNode payload = getJson("/repos/" + canonical);
        String fullName = payload.path("full_name").asText(canonical);
        return new GithubRepositoryMetadata(
                fullName,
                payload.path("owner").path("login").asText(null),
                payload.path("name").asText(canonical),
                payload.path("description").asText(null),
                payload.path("private").asBoolean(false),
                payload.path("default_branch").asText(null)
        );
    }

    public GithubPullRequestMetadata getPullRequest(String repoFullName, int pullRequestNumber) {
        String canonical = normalizeRepositoryName(repoFullName);
        JsonNode payload = getJson("/repos/" + canonical + "/pulls/" + pullRequestNumber);
        return new GithubPullRequestMetadata(
                payload.path("number").asInt(pullRequestNumber),
                payload.path("title").asText("Untitled PR"),
                payload.path("state").asText("open"),
                payload.path("user").path("login").asText("unknown"),
                payload.path("html_url").asText(null)
        );
    }

    public List<GithubChangedFile> getPullRequestFiles(String repoFullName, int pullRequestNumber) {
        String canonical = normalizeRepositoryName(repoFullName);
        String nextUrl = appProperties.getGithub().getApiBaseUrl().replaceAll("/$", "") + "/repos/" + canonical + "/pulls/" + pullRequestNumber + "/files";
        List<GithubChangedFile> files = new ArrayList<>();

        while (nextUrl != null) {
            ResponseEntity<String> response = request(nextUrl);
            JsonNode payload = parseJson(response.getBody());
            if (payload.isArray()) {
                for (JsonNode fileNode : payload) {
                    files.add(new GithubChangedFile(
                            fileNode.path("filename").asText(null),
                            fileNode.path("additions").asInt(0),
                            fileNode.path("deletions").asInt(0),
                            fileNode.path("patch").asText(null)
                    ));
                }
            }
            nextUrl = parseNextLinkFrom(response.getHeaders().getFirst("Link"));
        }
        return files;
    }

    public Instant getRateLimitReset() {
        return lastRateLimitReset;
    }

    private JsonNode getJson(String path) {
        return parseJson(request(appProperties.getGithub().getApiBaseUrl().replaceAll("/$", "") + path).getBody());
    }

    private ResponseEntity<String> request(String requestUrl) {
        return request(requestUrl, 0);
    }

    private ResponseEntity<String> request(String requestUrl, int attempt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.parseMediaType("application/vnd.github+json")));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (appProperties.getGithub().getToken() != null && !appProperties.getGithub().getToken().isBlank()) {
            headers.setBearerAuth(appProperties.getGithub().getToken());
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(requestUrl, HttpMethod.GET, entity, String.class);
            setRateLimitReset(response.getHeaders());
            if (response.getStatusCode().is2xxSuccessful()) {
                return response;
            }
            throw new GithubApiException("GitHub API request failed for " + requestUrl + " with status " + response.getStatusCode().value());
        } catch (HttpClientErrorException | HttpServerErrorException exception) {
            if (shouldRetry(exception, attempt)) {
                return request(requestUrl, attempt + 1);
            }
            handleRateLimit(exception);
            throw new GithubApiException("GitHub API request failed for " + requestUrl + ": " + exception.getStatusCode() + " " + exception.getResponseBodyAsString(), exception);
        } catch (RestClientException exception) {
            if (attempt < MAX_RETRIES) {
                return request(requestUrl, attempt + 1);
            }
            throw new GithubApiException("GitHub connectivity error for " + requestUrl, exception);
        }
    }

    private boolean shouldRetry(HttpStatusCodeException exception, int attempt) {
        if (attempt >= MAX_RETRIES) {
            return false;
        }
        int status = exception.getStatusCode().value();
        return status == 429 || status >= 500;
    }

    private void handleRateLimit(HttpStatusCodeException exception) {
        String resetHeader = exception.getResponseHeaders() != null ? exception.getResponseHeaders().getFirst("X-RateLimit-Reset") : null;
        if (resetHeader != null) {
            try {
                lastRateLimitReset = Instant.ofEpochSecond(Long.parseLong(resetHeader));
            } catch (NumberFormatException ignored) {
                lastRateLimitReset = Instant.EPOCH;
            }
        }
        String remaining = exception.getResponseHeaders() != null ? exception.getResponseHeaders().getFirst("X-RateLimit-Remaining") : null;
        if (remaining != null && "0".equals(remaining)) {
            throw new GithubApiException("GitHub API rate limit exceeded. Reset at " + lastRateLimitReset);
        }
    }

    private void setRateLimitReset(HttpHeaders headers) {
        if (headers == null) {
            return;
        }
        String resetHeader = headers.getFirst("X-RateLimit-Reset");
        if (resetHeader != null) {
            try {
                lastRateLimitReset = Instant.ofEpochSecond(Long.parseLong(resetHeader));
            } catch (NumberFormatException ignored) {
                lastRateLimitReset = Instant.EPOCH;
            }
        }
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new GithubApiException("Unable to parse GitHub response payload", exception);
        }
    }

    private String parseNextLinkFrom(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }
        Matcher matcher = NEXT_LINK_PATTERN.matcher(linkHeader);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

}
