package com.endpointguard.risk.repository;

import com.endpointguard.risk.domain.PostMergeMonitoring;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PostMergeMonitoringRepository extends JpaRepository<PostMergeMonitoring, Long> {

    List<PostMergeMonitoring> findByPullRequestIdOrderByWindowEndDesc(Long pullRequestId);

    List<PostMergeMonitoring> findByEndpointIdOrderByWindowEndDesc(Long endpointId);

    boolean existsByPullRequestIdAndEndpointId(Long pullRequestId, Long endpointId);

    List<PostMergeMonitoring> findByVerdictAndWindowEndLessThanEqual(String verdict, LocalDateTime windowEnd);

    void deleteByEndpointId(Long endpointId);

    void deleteByPullRequestId(Long pullRequestId);
}
