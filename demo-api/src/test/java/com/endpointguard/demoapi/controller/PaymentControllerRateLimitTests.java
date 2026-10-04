package com.endpointguard.demoapi.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentControllerRateLimitTests {

    @Test
    void rejectsRequestsAfterThePerMinuteLimit() {
        PaymentController controller = new PaymentController();

        for (int request = 1; request <= 100; request++) {
            assertThat(controller.isRateLimitExceeded()).isFalse();
        }

        assertThat(controller.isRateLimitExceeded()).isTrue();
    }
}
