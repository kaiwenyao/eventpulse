package dev.kaiwen.eventpulse.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.ConsumedEvent;

/**
 * 消费幂等仓库：只负责「尝试登记 (consumer_group, dedup_key) 是否首次处理」，
 * 不负责创建通知或 interaction。
 */
public interface ConsumedEventRepository extends JpaRepository<ConsumedEvent, ConsumedEvent.Key> {

    /**
     * 尝试插入一条消费记录。返回 1 表示本次是首次完整处理；
     * 返回 0 表示以前已经处理过（重复投递），应直接结束。
     */
    @Modifying
    @Query("""
            insert into ConsumedEvent (consumerGroup, dedupKey)
            select :consumerGroup, :dedupKey
            where not exists (
                select 1 from ConsumedEvent e
                 where e.consumerGroup = :consumerGroup
                   and e.dedupKey = :dedupKey)
            """)
    int tryInsert(@Param("consumerGroup") String consumerGroup, @Param("dedupKey") String dedupKey);
}
