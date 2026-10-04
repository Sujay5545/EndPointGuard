package com.endpointguard.pullrequest.repository;

import com.endpointguard.pullrequest.domain.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    Optional<WebhookEvent> findByGithubDeliveryId(String githubDeliveryId);
}