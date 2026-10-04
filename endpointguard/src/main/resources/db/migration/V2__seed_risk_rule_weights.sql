-- V2: Seed default risk rule weights
INSERT INTO risk_rule_weights (factor_name, weight, active, description) VALUES
    ('TRAFFIC_VOLUME', 0.20, true, 'Risk from high request volume on affected endpoint'),
    ('RATE_LIMIT_UTILIZATION', 0.20, true, 'Risk from high rate-limit utilization percentage'),
    ('ERROR_RATE_TREND', 0.25, true, 'Risk from rising error rate compared to baseline'),
    ('LATENCY_TREND', 0.15, true, 'Risk from rising latency compared to baseline'),
    ('DIFF_SIZE', 0.10, true, 'Risk from large diff size (lines changed)'),
    ('HISTORICAL_INCIDENTS', 0.10, true, 'Risk from prior incidents on the affected endpoint');
