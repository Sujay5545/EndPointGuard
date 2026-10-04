package com.endpointguard.review.provider;

import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;

public interface LlmReviewProvider {
    String providerName();

    ReviewDecision review(ReviewRequest request);
}
