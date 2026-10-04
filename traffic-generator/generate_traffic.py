#!/usr/bin/env python3
"""
EndpointGuard Traffic Generator
Sends realistic traffic to the demo-api with deliberate error/slow scenarios.
"""
import os
import time
import random
import logging
import requests
from datetime import datetime

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)s %(message)s'
)
logger = logging.getLogger(__name__)

BASE_URL = os.environ.get('DEMO_API_URL', 'http://localhost:8081')
REQUESTS_PER_SECOND = float(os.environ.get('REQUESTS_PER_SECOND', '5'))
SLEEP_INTERVAL = 1.0 / REQUESTS_PER_SECOND

# Scenario profiles
SCENARIOS = [
    # (weight, endpoint, method, payload_fn, description)
    (25, '/api/products', 'GET', None, 'List products (high traffic, normal)'),
    (15, '/api/products/1', 'GET', None, 'Get product detail'),
    (10, '/api/products', 'POST', lambda: {'name': 'Test Product', 'category': 'test', 'price': 9.99, 'stock': 10}, 'Create product'),
    (20, '/api/orders', 'GET', None, 'List orders'),
    (20, '/api/orders', 'POST', lambda: {'customerId': f'customer-{random.randint(1,100)}', 'productIds': [1, 2], 'total': 1329.98, 'status': 'PENDING'}, 'Create order (high latency)'),
    (10, '/api/payments', 'POST', lambda: {'orderId': random.randint(1000, 9999), 'amount': round(random.uniform(10, 5000), 2), 'method': random.choice(['CARD', 'BANK', 'CRYPTO'])}, 'Process payment (critical)'),
]

weights = [s[0] for s in SCENARIOS]

def pick_scenario():
    return random.choices(SCENARIOS, weights=weights, k=1)[0]

def send_request(session, scenario):
    weight, path, method, payload_fn, desc = scenario
    url = BASE_URL + path
    payload = payload_fn() if payload_fn else None
    try:
        start = time.time()
        if method == 'GET':
            resp = session.get(url, timeout=10)
        elif method == 'POST':
            resp = session.post(url, json=payload, timeout=10)
        latency_ms = (time.time() - start) * 1000
        logger.debug(f"{method} {path} -> {resp.status_code} ({latency_ms:.0f}ms)")
        return resp.status_code, latency_ms
    except requests.exceptions.RequestException as e:
        logger.warning(f"Request failed {method} {path}: {e}")
        return None, None

def wait_for_api():
    logger.info(f"Waiting for demo API at {BASE_URL}...")
    for attempt in range(30):
        try:
            resp = requests.get(f"{BASE_URL}/actuator/health", timeout=5)
            if resp.status_code == 200:
                logger.info("Demo API is up!")
                return True
        except Exception:
            pass
        time.sleep(2)
    logger.error("Demo API did not come up in time")
    return False

def main():
    logger.info(f"Starting traffic generator -> {BASE_URL} at {REQUESTS_PER_SECOND} req/s")
    if not wait_for_api():
        return

    session = requests.Session()
    total = 0
    errors = 0
    start_time = time.time()

    while True:
        scenario = pick_scenario()
        status, latency = send_request(session, scenario)
        total += 1
        if status and status >= 400:
            errors += 1

        if total % 100 == 0:
            elapsed = time.time() - start_time
            error_rate = errors / total * 100
            logger.info(f"Stats: {total} requests, {error_rate:.1f}% error rate, {elapsed:.0f}s elapsed")

        time.sleep(SLEEP_INTERVAL)

if __name__ == '__main__':
    main()
