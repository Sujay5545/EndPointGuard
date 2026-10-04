package com.endpointguard.endpoint.repository;

import com.endpointguard.endpoint.domain.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EndpointRepository extends JpaRepository<Endpoint, Long> {
	List<Endpoint> findByProjectIdOrderByPathPatternAsc(Long projectId);

	List<Endpoint> findByRepositoryIdOrderByPathPatternAsc(Long repositoryId);

	List<Endpoint> findByRepositoryId(Long repositoryId);

	Optional<Endpoint> findByIdAndRepositoryId(Long id, Long repositoryId);

	List<Endpoint> findByMethodIgnoreCaseAndPathPattern(String method, String pathPattern);

	List<Endpoint> findByProjectIdAndRepositoryIdIsNullOrderByPathPatternAsc(Long projectId);

	@Query("""
		SELECT endpoint FROM Endpoint endpoint
		WHERE endpoint.id = :endpointId
		AND EXISTS (
			SELECT project.id FROM Project project
			WHERE project.id = endpoint.projectId
			AND project.owner.email = :ownerEmail
		)
		""")
	Optional<Endpoint> findOwnedById(
			@Param("endpointId") Long endpointId,
			@Param("ownerEmail") String ownerEmail);
}