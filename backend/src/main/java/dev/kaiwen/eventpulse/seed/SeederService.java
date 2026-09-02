package dev.kaiwen.eventpulse.seed;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.entity.SeedRun;
import dev.kaiwen.eventpulse.repository.SeedRunRepository;

/**
 * 播种服务（仅 seeder Profile）：把演示数据写入放在一个数据库事务里，
 * 全部成功才提交并记录 seed_runs；中途失败全部回滚，不留下「已经完成」的记录。
 *
 * seed_name 唯一约束 + 提交前检查：Job 重试或人工重跑时，已完成的版本直接跳过，
 * 不会生成重复数据。
 */
@Service
@Profile("seeder")
public class SeederService {

    private static final Logger log = LoggerFactory.getLogger(SeederService.class);

    private final SeedRunRepository seedRuns;
    private final DemoDataSeeder demoData;
    private final String seedName;

    public SeederService(SeedRunRepository seedRuns, DemoDataSeeder demoData,
            @org.springframework.beans.factory.annotation.Value("${eventpulse.seed-name:demo-v1}") String seedName) {
        this.seedRuns = seedRuns;
        this.demoData = demoData;
        this.seedName = seedName;
    }

    /**
     * @return true 表示本次执行了播种；false 表示该版本已经完成，直接跳过。
     */
    @Transactional
    public boolean seed() {
        if (seedRuns.existsById(seedName)) {
            log.info("Seed 「{}」已经完成，跳过播种", seedName);
            return false;
        }
        demoData.seed();
        seedRuns.save(new SeedRun(seedName, Instant.now()));
        log.info("Seed 「{}」播种完成", seedName);
        return true;
    }
}
