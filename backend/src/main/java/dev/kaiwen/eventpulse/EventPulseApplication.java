package dev.kaiwen.eventpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import dev.kaiwen.eventpulse.common.AppProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class EventPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventPulseApplication.class, args);
    }
}
