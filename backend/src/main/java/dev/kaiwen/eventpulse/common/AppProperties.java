package dev.kaiwen.eventpulse.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "eventpulse")
public record AppProperties(Security security, Booking booking, Commands commands, Relay relay,
                            Gateway gateway, RateLimit rateLimit, Seed seed) {

    public record Security(String secretKey, String tokenPepper, Duration accessTokenTtl,
                           Duration refreshTokenTtl, Duration adminReauthTtl, List<String> corsAllowedOrigins,
                           Boolean refreshCookieSecure) {

        public boolean secretsAreDefaults() {
            return secretKey == null || secretKey.startsWith("dev-only")
                    || tokenPepper == null || tokenPepper.startsWith("dev-only");
        }
    }

    public record Booking(Duration ttl, int maxPerBooking, Duration expiryScanInterval) {
    }

    public record Commands(Duration dispatcherInterval, int batchSize, Duration lease, int maxAttempts,
                           Duration unknownResolveInterval) {
    }

    public record Relay(Duration interval, int batchSize) {
    }

    /**
     * Scenario rules are a compact server-side spec string, parsed lazily:
     * "prefix:scenario:delaySeconds;prefix2:scenario2:delaySeconds".
     * The prod profile must keep it empty (asserted at startup).
     */
    public record Gateway(String scenarioRulesSpec, Duration unknownResolveInterval) {

        public List<ScenarioRule> parsedRules() {
            if (scenarioRulesSpec == null || scenarioRulesSpec.isBlank()) {
                return List.of();
            }
            java.util.ArrayList<ScenarioRule> rules = new java.util.ArrayList<>();
            for (String entry : scenarioRulesSpec.split(";")) {
                if (entry.isBlank()) {
                    continue;
                }
                String[] parts = entry.split(":");
                if (parts.length >= 2) {
                    rules.add(new ScenarioRule(parts[0].trim(), parts[1].trim().toUpperCase(),
                            parts.length >= 3 ? Integer.parseInt(parts[2].trim()) : 3));
                }
            }
            return rules;
        }
    }

    public record ScenarioRule(String providerKeyPrefix, String scenario, int delaySeconds) {
    }

    public record RateLimit(String login, String register, String redeem, String interactions, String reauth) {

        public long limit(String key) {
            String spec = switch (key) {
                case "login" -> login;
                case "register" -> register;
                case "redeem" -> redeem;
                case "interactions" -> interactions;
                case "reauth" -> reauth;
                default -> "60/60";
            };
            return Long.parseLong(spec.split("/")[0]);
        }

        public long windowSeconds(String key) {
            String spec = switch (key) {
                case "login" -> login;
                case "register" -> register;
                case "redeem" -> redeem;
                case "interactions" -> interactions;
                case "reauth" -> reauth;
                default -> "60/60";
            };
            return Long.parseLong(spec.split("/")[1]);
        }
    }

    public record Seed(boolean enabled) {
    }
}
