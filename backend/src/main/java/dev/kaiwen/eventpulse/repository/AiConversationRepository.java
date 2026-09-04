package dev.kaiwen.eventpulse.repository;

import java.time.Instant;
import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.AiConversation;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    /** 用户的会话列表，最近更新的在前（走 ix_ai_conversations_user）。 */
    Page<AiConversation> findByUserIdAndKindOrderByUpdatedAtDesc(Long userId, String kind, Pageable pageable);

    /** 保留期清理用：全局按 updated_at 扫描（走 V5 新增的 ix_ai_conversations_updated）。 */
    Page<AiConversation> findByUpdatedAtBefore(Instant cutoff, Pageable pageable);

    /**
     * 批量删除写成显式 JPQL 而不是派生的 deleteByIdIn：派生删除会先把实体查出来再
     * 逐条删，且要求调用方处在事务里；清理 worker 是按批处理的，用一条 DELETE 更直接。
     */
    @Modifying
    @Query("delete from AiConversation c where c.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<Long> ids);
}
