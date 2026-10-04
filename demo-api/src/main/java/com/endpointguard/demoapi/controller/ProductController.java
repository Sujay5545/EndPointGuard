package com.endpointguard.demoapi.controller;

import com.endpointguard.demoapi.model.Product;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final List<Product> PRODUCTS = List.of(
        new Product(1L, "Laptop Pro", "electronics", 1299.99, 50),
        new Product(2L, "Wireless Mouse", "accessories", 29.99, 200),
        new Product(3L, "Mechanical Keyboard", "accessories", 149.99, 75),
        new Product(4L, "4K Monitor", "electronics", 599.99, 30),
        new Product(5L, "USB Hub", "accessories", 49.99, 150)
    );

    @GetMapping
    public List<Product> listProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) throws InterruptedException {
        simulateLatency(20, 80);
        return PRODUCTS;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable Long id) throws InterruptedException {
        simulateLatency(10, 50);
        return PRODUCTS.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Product> createProduct(@RequestBody Product product) throws InterruptedException {
        simulateLatency(30, 120);
        // Occasionally simulate server errors (2% chance)
        if (ThreadLocalRandom.current().nextInt(100) < 2) {
            return ResponseEntity.internalServerError().build();
        }
        Product created = new Product(
            ThreadLocalRandom.current().nextLong(100, 10000),
            product.name(), product.category(), product.price(), product.stock()
        );
        return ResponseEntity.status(201).body(created);
    }

    private void simulateLatency(int minMs, int maxMs) throws InterruptedException {
        Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs));
    }
}
