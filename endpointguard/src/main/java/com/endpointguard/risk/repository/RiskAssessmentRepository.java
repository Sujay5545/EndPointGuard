package com.endpointguard.risk.repository;

import com.endpointguard.risk.domain.RiskAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, Long> {

    List<RiskAssessment> findByPullRequestIdOrderByComputedAtDesc(Long pullRequestId);

    void deleteByPullRequestId(Long pullRequestId);
}
