package com.endpointguard.pullrequest.repository;

import com.endpointguard.pullrequest.domain.PrChangedFile;
import com.endpointguard.pullrequest.domain.PullRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrChangedFileRepository extends JpaRepository<PrChangedFile, Long> {

    List<PrChangedFile> findByPullRequestOrderByFilePathAsc(PullRequest pullRequest);

    void deleteByPullRequest_Id(Long pullRequestId);
}
