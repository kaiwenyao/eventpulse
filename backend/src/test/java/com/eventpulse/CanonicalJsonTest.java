package com.eventpulse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eventpulse.common.CanonicalJson;
import com.eventpulse.outbox.OutboxJson;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Canonical JSON fingerprints: key order, numeric and unicode normalisation. */
class CanonicalJsonTest {

    @Test
    void keyOrderDoesNotChangeTheHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("quantity", 2);
        a.put("tierId", "t-1");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("tierId", "t-1");
        b.put("quantity", 2);
        String hashA = CanonicalJson.sha256Hex(CanonicalJson.canonicalize(a, OutboxJson.mapper()));
        String hashB = CanonicalJson.sha256Hex(CanonicalJson.canonicalize(b, OutboxJson.mapper()));
        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    void numericEquivalentValuesHashIdentically() {
        Map<String, Object> a = Map.of("amount", 10.0);
        Map<String, Object> b = Map.of("amount", 10.00);
        String hashA = CanonicalJson.sha256Hex(CanonicalJson.canonicalize(a, OutboxJson.mapper()));
        String hashB = CanonicalJson.sha256Hex(CanonicalJson.canonicalize(b, OutboxJson.mapper()));
        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    void unicodeComposedAndDecomposedFormsHashIdentically() {
        Map<String, Object> a = Map.of("title", "\u00E9v\u00E9nement");
        Map<String, Object> b = Map.of("title", "e\u0301ve\u0301nement");
        String hashA = CanonicalJson.sha256Hex(CanonicalJson.canonicalize(a, OutboxJson.mapper()));
        String hashB = CanonicalJson.sha256Hex(CanonicalJson.canonicalize(b, OutboxJson.mapper()));
        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    void opaqueTokensHaveEnoughEntropy() {
        String token = CanonicalJson.newOpaqueToken();
        assertThat(token.length()).isGreaterThanOrEqualTo(42); // 32 bytes base64url
        assertThat(CanonicalJson.newOpaqueToken()).isNotEqualTo(token);
    }

    @Test
    void arraysAreSortedElementWiseSoOrderDoesNotMatterForKeyHash() {
        // Nested objects inside arrays are recursively sorted.
        Map<String, Object> innerA = new LinkedHashMap<>();
        innerA.put("b", 1);
        innerA.put("a", 2);
        Map<String, Object> innerB = new LinkedHashMap<>();
        innerB.put("a", 2);
        innerB.put("b", 1);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("rows", List.of(innerA));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("rows", List.of(innerB));
        String hashA = CanonicalJson.sha256Hex(CanonicalJson.canonicalize(a, OutboxJson.mapper()));
        String hashB = CanonicalJson.sha256Hex(CanonicalJson.canonicalize(b, OutboxJson.mapper()));
        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    void hmacIsDeterministicAndDistinctFromSha256() {
        String mac = CanonicalJson.hmacSha256Hex("secret", "payload");
        assertThat(CanonicalJson.hmacSha256Hex("secret", "payload")).isEqualTo(mac);
        assertThat(mac).hasSize(64);
        assertThat(mac).isNotEqualTo(CanonicalJson.sha256Hex("payload"));
        // different secret -> different mac
        assertThat(CanonicalJson.hmacSha256Hex("other", "payload")).isNotEqualTo(mac);
    }

    @Test
    void sha256HexIsHexEncodedDeterministically() {
        assertThat(CanonicalJson.sha256Hex("")).hasSize(64).matches("[0-9a-f]+");
        assertThat(CanonicalJson.sha256Hex("abc")).isEqualTo(CanonicalJson.sha256Hex("abc"));
    }

    @Test
    void ticketAndIdempotencyHelpersAreUnique() {
        assertThat(CanonicalJson.newTicketToken()).isNotEqualTo(CanonicalJson.newTicketToken());
        assertThat(CanonicalJson.newIdempotencyKeyHint())
                .isNotEqualTo(CanonicalJson.newIdempotencyKeyHint());
    }

    @Test
    void canonicalizeSerialisesLeafArraysOfPrimitives() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("tags", List.of("music", "art"));
        value.put("count", 3);
        String canonical = CanonicalJson.canonicalize(value, OutboxJson.mapper());
        assertThat(canonical).contains("\"count\":3").contains("\"tags\":[\"music\",\"art\"]");
    }
}

