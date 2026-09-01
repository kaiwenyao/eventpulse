package dev.kaiwen.eventpulse.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "recommendation_requests")
public class RecommendationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true)
    private String requestId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "partition_key")
    private String partitionKey;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Column(name = "feature_version", nullable = false)
    private String featureVersion;

    @Column(name = "frozen_candidates", nullable = false)
    private String frozenCandidates;

    @Column(name = "queried_at", nullable = false)
    private Instant queriedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public void setPartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getFeatureVersion() {
        return featureVersion;
    }

    public void setFeatureVersion(String featureVersion) {
        this.featureVersion = featureVersion;
    }

    public String getFrozenCandidates() {
        return frozenCandidates;
    }

    public void setFrozenCandidates(String frozenCandidates) {
        this.frozenCandidates = frozenCandidates;
    }

    public Instant getQueriedAt() {
        return queriedAt;
    }

    public void setQueriedAt(Instant queriedAt) {
        this.queriedAt = queriedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
