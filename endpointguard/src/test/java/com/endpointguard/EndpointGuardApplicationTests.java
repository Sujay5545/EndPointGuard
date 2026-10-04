package com.endpointguard;

import com.endpointguard.common.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class EndpointGuardApplicationTests extends RollbackIntegrationTest {
    @Autowired
    private AppProperties appProperties;

    @Test
    void contextLoads() {
        // DoD: Spring context loads without errors
        org.assertj.core.api.Assertions.assertThat(appProperties.getLlm().getTimeoutSeconds()).isEqualTo(30);
        org.assertj.core.api.Assertions.assertThat(appProperties.getHttp().getSharedTimeoutSeconds()).isEqualTo(5);
    }
}
