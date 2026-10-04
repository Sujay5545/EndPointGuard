package com.endpointguard.project.repository;

import com.endpointguard.project.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByOwnerEmailOrderByCreatedAtDesc(String email);
    Optional<Project> findByIdAndOwnerEmail(Long id, String email);
}