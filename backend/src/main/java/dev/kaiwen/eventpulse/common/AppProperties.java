package dev.kaiwen.eventpulse.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "eventpulse")
public record AppProperties(Security security, Booking booking, Commands commands, Relay relay,
                            Wallet wallet, RateLimit rateLimit, Seed seed) {

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

    /** Demo signup grant credited onto a new user_wallets row. Not a top-up API. */
    public record Wallet(long signupGrantMinor) {
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
