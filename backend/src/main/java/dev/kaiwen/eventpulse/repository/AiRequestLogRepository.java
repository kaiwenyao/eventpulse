package dev.kaiwen.eventpulse.repository;

import java.time.Instant;
import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.AiRequestLog;

public interface AiRequestLogRepository extends JpaRepository<AiRequestLog, String> {

    /** 保留期清理用；走既有的 ix_ai_requests_created。 */
    Page<AiRequestLog> findByCreatedAtBefore(Instant cutoff, Pageable pageable);

    @Modifying
    @Query("delete from AiRequestLog r where r.requestId in :ids")
    int deleteByRequestIdIn(@Param("ids") Collection<String> ids);
}
