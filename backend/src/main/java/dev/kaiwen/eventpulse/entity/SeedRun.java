package dev.kaiwen.eventpulse.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一次演示数据播种的完成记录。seed_name 有唯一约束：Seeder 执行成功才写入，
 * 失败回滚不留下记录，因此 Kubernetes Job 重试或人工重跑不会产生重复数据。
 */
@Entity
@Table(name = "seed_runs")
public class SeedRun {

    /** 初始化任务名称，例如 demo-v1。 */
    @Id
    @Column(name = "seed_name", length = 100)
    private String seedName;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    public SeedRun() {
    }

    public SeedRun(String seedName, Instant completedAt) {
        this.seedName = seedName;
        this.completedAt = completedAt;
    }

    public String getSeedName() {
        return seedName;
    }

    public void setSeedName(String seedName) {
        this.seedName = seedName;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
