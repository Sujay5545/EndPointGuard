package com.endpointguard.demoapi.controller;

import com.endpointguard.demoapi.DemoApiLimits;
import com.endpointguard.demoapi.model.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final AtomicLong idCounter = new AtomicLong(500);
    // Simulate a rate-limit counter (very simplified)
    private int requestsInCurrentMinute = 0;
    private long minuteStart = System.currentTimeMillis();

    @PostMapping
    public ResponseEntity<Payment> processPayment(@RequestBody Payment payment) throws InterruptedException {
        if (isRateLimitExceeded()) {
            return ResponseEntity.status(429).build();
        }

        simulateLatency(50, 200);
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 5) {
            // 5% payment failures (realistic)
            return ResponseEntity.badRequest().build();
        } else if (roll < 8) {
            // 3% server-side payment processor errors
            return ResponseEntity.internalServerError().build();
        }

        Payment processed = new Payment(
            idCounter.incrementAndGet(),
            payment.orderId(),
            payment.amount(),
            "COMPLETED",
            payment.method() != null ? payment.method() : "CARD",
            LocalDateTime.now()
        );
        return ResponseEntity.status(201).body(processed);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Payment> getPayment(@PathVariable Long id) throws InterruptedException {
        simulateLatency(20, 80);
        return ResponseEntity.ok(new Payment(id, 1001L, 99.99, "COMPLETED", "CARD", LocalDateTime.now().minusHours(1)));
    }

    private void simulateLatency(int minMs, int maxMs) throws InterruptedException {
        Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs));
    }

    synchronized boolean isRateLimitExceeded() {
        long now = System.currentTimeMillis();
        if (now - minuteStart >= 60000) {
            requestsInCurrentMinute = 0;
            minuteStart = now;
        }
        requestsInCurrentMinute++;

        if (requestsInCurrentMinute > DemoApiLimits.PAYMENT_REQUESTS_PER_MINUTE * 0.8) {
            log.warn("Payment endpoint approaching rate limit: {}/{}", requestsInCurrentMinute, DemoApiLimits.PAYMENT_REQUESTS_PER_MINUTE);
        }
        return requestsInCurrentMinute > DemoApiLimits.PAYMENT_REQUESTS_PER_MINUTE;
    }
}
