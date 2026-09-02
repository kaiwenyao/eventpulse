package dev.kaiwen.eventpulse.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.SpringApplication;

/**
 * seeder 角色入口（Kubernetes Job / Compose 一次性任务）：
 * 调用带事务的 {@link SeederService}，完成后主动退出；
 * 失败返回非零退出码，让 Kubernetes 把 Job 标记为失败并按 backoffLimit 重试。
 */
@Component
@Profile("seeder")
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final SeederService seederService;
    private final ApplicationContext context;

    public DataSeeder(SeederService seederService, ApplicationContext context) {
        this.seederService = seederService;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        int exitCode = runSeed();
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private int runSeed() {
        try {
            boolean seeded = seederService.seed();
            log.info("Seeder 结束：{}", seeded ? "本次完成播种" : "数据已存在，未重复播种");
            return 0;
        }
        catch (Exception e) {
            log.error("Seeder 执行失败", e);
            return 1;
        }
    }
}
