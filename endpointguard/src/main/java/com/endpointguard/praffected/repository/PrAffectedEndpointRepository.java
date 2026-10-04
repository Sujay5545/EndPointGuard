package com.endpointguard.praffected.repository;

import com.endpointguard.endpoint.domain.Endpoint;
import com.endpointguard.praffected.domain.PrAffectedEndpoint;
import com.endpointguard.praffected.domain.PrAffectedEndpointId;
import com.endpointguard.pullrequest.domain.PullRequest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrAffectedEndpointRepository extends JpaRepository<PrAffectedEndpoint, PrAffectedEndpointId> {

    @Override
    @EntityGraph(attributePaths = {"pullRequest", "endpoint"})
    List<PrAffectedEndpoint> findAll();

    @EntityGraph(attributePaths = {"pullRequest", "endpoint"})
    List<PrAffectedEndpoint> findByPullRequestOrderByEndpointAsc(PullRequest pullRequest);

    @EntityGraph(attributePaths = {"pullRequest", "endpoint"})
    List<PrAffectedEndpoint> findByEndpointOrderByPullRequestAsc(Endpoint endpoint);

    boolean existsByPullRequest_IdAndEndpoint_Id(Long pullRequestId, Long endpointId);

    void deleteByPullRequest_Id(Long pullRequestId);

    void deleteByEndpoint_Id(Long endpointId);
}
