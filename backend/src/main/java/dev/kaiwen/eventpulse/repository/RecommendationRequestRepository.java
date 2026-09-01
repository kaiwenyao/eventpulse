package dev.kaiwen.eventpulse.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.RecommendationRequest;

public interface RecommendationRequestRepository extends JpaRepository<RecommendationRequest, Long> {

    Optional<RecommendationRequest> findByRequestId(String requestId);
}
