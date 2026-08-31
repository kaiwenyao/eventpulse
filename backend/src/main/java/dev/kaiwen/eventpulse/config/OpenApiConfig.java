package dev.kaiwen.eventpulse.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI eventPulseOpenApi() {
        return new OpenAPI().info(new Info()
                .title("EventPulse API")
                .description("Personalised event discovery and ticketing platform. "
                        + "Object-existence and authorization failures share one hidden-object policy (404). "
                        + "Write endpoints require an Idempotency-Key (>= 32 random chars).")
                .version("v1"));
    }
}
