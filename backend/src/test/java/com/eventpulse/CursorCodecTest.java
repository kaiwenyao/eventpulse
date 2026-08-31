package com.eventpulse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eventpulse.catalogue.CursorCodec;
import com.eventpulse.catalogue.SearchCursor;
import com.eventpulse.common.AppProperties;
import com.eventpulse.common.error.ApiException;
import com.eventpulse.common.error.ErrorCode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Signed cursor codec: roundtrip, tampering, expiry, version drift. */
class CursorCodecTest {

    private AppProperties properties(String secret) {
        return new AppProperties(
                new AppProperties.Security(secret, "pepper", java.time.Duration.ofMinutes(15),
                        java.time.Duration.ofDays(30), java.time.Duration.ofMinutes(10), List.of()),
                null, null, null, null, null, null);
    }

    @Test
    void roundtripPreservesTupleQueryAsOfAndExpiry() {
        CursorCodec codec = new CursorCodec(properties("unit-secret"));
        Instant queryAsOf = Instant.parse("2026-08-30T10:00:00Z");
        SearchCursor cursor = new SearchCursor(1, "filter-hash", "price",
                List.of("12345", UUID.randomUUID().toString()), queryAsOf, codec.newExpiry());
        SearchCursor decoded = codec.decode(codec.encode(cursor));
        assertThat(decoded.version()).isEqualTo(1);
        assertThat(decoded.filterHash()).isEqualTo("filter-hash");
        assertThat(decoded.sort()).isEqualTo("price");
        assertThat(decoded.queryAsOf()).isEqualTo(queryAsOf);
        assertThat(decoded.last().get(0)).isEqualTo("12345");
    }

    @Test
    void tamperedSignatureIsRejected() {
        CursorCodec codec = new CursorCodec(properties("unit-secret"));
        String token = codec.encode(new SearchCursor(1, "f", "price", List.of("1", "x"),
                Instant.now(), Instant.now().plusSeconds(60)));
        assertThatThrownBy(() -> codec.decode(token.substring(0, token.length() - 2) + "zz"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CURSOR_INVALID));
    }

    @Test
    void foreignSecretRejectsTheCursor() {
        String token = new CursorCodec(properties("secret-a")).encode(
                new SearchCursor(1, "f", "starts_at", List.of("1", "x"), Instant.now(),
                        Instant.now().plusSeconds(60)));
        assertThatThrownBy(() -> new CursorCodec(properties("secret-b")).decode(token))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void expiredCursorIsRejected() {
        CursorCodec codec = new CursorCodec(properties("unit-secret"));
        String token = codec.encode(new SearchCursor(1, "f", "starts_at", List.of("1", "x"),
                Instant.now(), Instant.now().minusSeconds(1)));
        assertThatThrownBy(() -> codec.decode(token))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CURSOR_EXPIRED));
    }

    @Test
    void malformedAndWrongVersionCursorsAreRejected() {
        CursorCodec codec = new CursorCodec(properties("unit-secret"));
        assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> codec.decode("no-signature")).isInstanceOf(ApiException.class);
        String wrongVersion = codec.encode(new SearchCursor(99, "f", "starts_at", List.of("1", "x"),
                Instant.now(), Instant.now().plusSeconds(60)));
        assertThatThrownBy(() -> codec.decode(wrongVersion)).isInstanceOf(ApiException.class);
        // garbage body with valid HMAC for that garbage is still unreadable
        assertThatThrownBy(() -> codec.decode("%%%nonbase64%%." + com.eventpulse.common.CanonicalJson
                .hmacSha256Hex("unit-secret", "%%%nonbase64%%"))).isInstanceOf(ApiException.class);
    }
}
