package com.eventpulse.common.config;

import com.eventpulse.common.AppProperties;

import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Week-one compatibility spike conclusion (ADR-005) plus production
 * assertions: the prod profile refuses default secrets and refuses
 * user-controllable gateway scenario rules.
 */
@Configuration
@org.springframework.context.annotation.Profile("prod")
public class ProdSecurityAssertions {

    private final AppProperties properties;

    public ProdSecurityAssertions(AppProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void assertHardening() {
        if (properties.security().secretsAreDefaults()) {
            throw new IllegalStateException("prod profile requires SECRET_KEY and TOKEN_PEPPER to be set");
        }
        if (!properties.gateway().parsedRules().isEmpty()) {
            throw new IllegalStateException("prod profile must not define gateway scenario rules");
        }
    }
}
