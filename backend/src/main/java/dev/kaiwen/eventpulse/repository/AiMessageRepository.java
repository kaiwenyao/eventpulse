package dev.kaiwen.eventpulse.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.AiMessage;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {

    /** 取某会话最近的消息（id 降序），调用方自己反转成时间正序。 */
    Page<AiMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);
}
