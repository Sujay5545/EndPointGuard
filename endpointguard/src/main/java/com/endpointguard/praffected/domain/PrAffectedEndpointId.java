package com.endpointguard.praffected.domain;

import java.io.Serializable;
import java.util.Objects;

public class PrAffectedEndpointId implements Serializable {
    private Long pullRequest;
    private Long endpoint;

    public PrAffectedEndpointId() {
    }

    public PrAffectedEndpointId(Long pullRequest, Long endpoint) {
        this.pullRequest = pullRequest;
        this.endpoint = endpoint;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PrAffectedEndpointId that = (PrAffectedEndpointId) o;
        return Objects.equals(pullRequest, that.pullRequest) && Objects.equals(endpoint, that.endpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pullRequest, endpoint);
    }
}
