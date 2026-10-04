package com.endpointguard;

import com.endpointguard.github.GithubChangedFile;
import com.endpointguard.github.GithubPullRequestMetadata;
import com.endpointguard.github.GithubRepositoryMetadata;
import com.endpointguard.github.GithubRestApiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Configuration(proxyBeanMethods = false)
@Profile("test")
class TestGithubClientConfiguration {

    @Bean
    GithubRestApiClient testGithubRestApiClient() {
        GithubRestApiClient client = mock(GithubRestApiClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.validateRepository(anyString())).thenAnswer(invocation -> {
            String fullName = invocation.getArgument(0);
            String[] parts = fullName.split("/", 2);
            return new GithubRepositoryMetadata(fullName, parts[0], parts[1], null, false, "main");
        });
        when(client.getPullRequest(anyString(), anyInt())).thenAnswer(invocation ->
                new GithubPullRequestMetadata(invocation.getArgument(1), "Test pull request", "open", "test", null));
        when(client.getPullRequestFiles(anyString(), anyInt())).thenReturn(List.<GithubChangedFile>of());
        return client;
    }
}