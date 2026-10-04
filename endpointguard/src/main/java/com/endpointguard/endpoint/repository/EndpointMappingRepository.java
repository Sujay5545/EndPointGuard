package com.endpointguard.endpoint.repository;

import com.endpointguard.endpoint.domain.EndpointMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EndpointMappingRepository extends JpaRepository<EndpointMapping, Long> {
    List<EndpointMapping> findByEndpointProjectIdOrderBySourcePatternAsc(Long projectId);

    List<EndpointMapping> findByEndpointIdOrderBySourcePatternAsc(Long endpointId);

    void deleteByEndpointId(Long endpointId);
}