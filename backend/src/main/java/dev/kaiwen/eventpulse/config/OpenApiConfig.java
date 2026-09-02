package dev.kaiwen.eventpulse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("EventPulse API")
                .version("0.1.0")
                .description("Event booking: Spring Boot CRUD + Kafka notifications"));
    }
}
