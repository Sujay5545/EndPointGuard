package com.endpointguard.risk.repository;

import com.endpointguard.risk.domain.RiskRuleWeight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RiskRuleWeightRepository extends JpaRepository<RiskRuleWeight, Long> {
    List<RiskRuleWeight> findByActiveTrueOrderByIdAsc();
}
