package com.endpointguard.demoapi.controller;

import com.endpointguard.demoapi.model.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
@RequestMapping("/api/orders")
public class OrderController {
// Testing PR request
    private final AtomicLong idCounter = new AtomicLong(1000);

    @GetMapping
    public List<Order> listOrders(
            @RequestParam(required = false) String customerId) throws InterruptedException {
        simulateLatency(30, 100);
        return List.of(
            new Order(idCounter.get(), "customer-1", List.of(1L, 2L), 1329.98, "DELIVERED", LocalDateTime.now().minusDays(1)),
            new Order(idCounter.get() + 1, "customer-2", List.of(4L), 599.99, "PROCESSING", LocalDateTime.now().minusHours(2))
        );
    }

    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody Order order) throws InterruptedException {
        // Orders endpoint simulates higher latency and occasional failures (realistic)
        simulateLatency(100, 300);
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 3) {
            // 3% server error
            return ResponseEntity.internalServerError().build();
        } else if (roll < 7) {
            // 4% slow path (extra delay already done above)
            Thread.sleep(200);
        }
        Order created = new Order(
            idCounter.incrementAndGet(),
            order.customerId(),
            order.productIds(),
            order.total(),
            "PENDING",
            LocalDateTime.now()
        );
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable Long id) throws InterruptedException {
        simulateLatency(20, 60);
        return ResponseEntity.ok(new Order(id, "customer-1", List.of(1L), 1299.99, "DELIVERED", LocalDateTime.now().minusDays(2)));
    }

    private void simulateLatency(int minMs, int maxMs) throws InterruptedException {
        Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs));
    }
}
