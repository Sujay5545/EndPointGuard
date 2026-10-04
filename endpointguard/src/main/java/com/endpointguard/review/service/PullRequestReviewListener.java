package com.endpointguard.review.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class PullRequestReviewListener {

    private final PullRequestReviewService pullRequestReviewService;

    @Async("pullRequestReviewExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void reviewAfterCommit(PullRequestReviewRequested event) {
        pullRequestReviewService.review(event.pullRequestId());
    }
}