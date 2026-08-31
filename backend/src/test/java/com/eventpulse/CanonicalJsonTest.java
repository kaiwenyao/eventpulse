package com.eventpulse;

import java.util.LinkedHashMap;
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
}
