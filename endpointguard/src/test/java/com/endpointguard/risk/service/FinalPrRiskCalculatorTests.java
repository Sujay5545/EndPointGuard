package com.endpointguard.risk.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class FinalPrRiskCalculatorTests {

    @ParameterizedTest
    @CsvSource({
            "0.80, 0.60, 0.80",
            "0.80, 0.90, 0.82",
            "0.20, 0.90, 0.34",
            "0.20, 0.10, 0.20",
            "0.00, 1.00, 0.20",
            "1.00, 0.00, 1.00"
    })
    void calculatesAiPrimaryFinalRisk(double aiRisk, double operationalRisk, double expected) {
        assertThat(FinalPrRiskCalculator.calculate(aiRisk, operationalRisk)).isCloseTo(expected, offset(1.0e-12));
    }

    @ParameterizedTest
    @CsvSource({"0.72", "0.385"})
    void preservesNormalizedAiAdvisoryInputs(double aiRisk) {
        assertThat(FinalPrRiskCalculator.calculate(aiRisk, 0.0)).isEqualTo(aiRisk);
    }

    @org.junit.jupiter.api.Test
    void hasNoFinalRiskWithoutAnAiAdvisoryScore() {
        assertThat(FinalPrRiskCalculator.calculate(null, 1.0)).isNull();
    }
}