package dev.kaiwen.eventpulse.repository;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.AiMessage;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {

    /** 取某会话最近的消息（id 降序），调用方自己反转成时间正序。 */
    Page<AiMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);

    /** 恢复会话时按时间正序读，前端直接铺进对话流。 */
    Page<AiMessage> findByConversationIdOrderByIdAsc(Long conversationId, Pageable pageable);

    /** ai_messages.conversation_id 是普通外键、没有 ON DELETE CASCADE：必须先删消息再删会话。 */
    @Modifying
    @Query("delete from AiMessage m where m.conversationId in :conversationIds")
    int deleteByConversationIdIn(@Param("conversationIds") Collection<Long> conversationIds);
}
