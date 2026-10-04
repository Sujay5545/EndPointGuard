package com.endpointguard.pullrequest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pr_changed_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrChangedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_request_id", nullable = false)
    private PullRequest pullRequest;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(nullable = false)
    @Builder.Default
    private Integer additions = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer deletions = 0;

    @Column(columnDefinition = "TEXT")
    private String patch;
}
