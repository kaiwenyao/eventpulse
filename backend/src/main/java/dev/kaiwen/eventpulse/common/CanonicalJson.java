package dev.kaiwen.eventpulse.common;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Versioned canonical JSON for request fingerprints and signed cursors:
 * recursively sorted object keys, NFC-normalised strings, integral numbers
 * rendered without trailing zeros. Trace/header values are excluded upstream
 * because only business DTOs are passed in.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String canonicalize(Object value, ObjectMapper mapper) {
        JsonNode node = mapper.valueToTree(value);
        JsonNode sorted = sort(node);
        return mapper.writeValueAsString(sorted);
    }

    public static String sha256Hex(String input) {
        return digestHex("SHA-256", input.getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacSha256Hex(String secret, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    public static String newOpaqueToken() {
        byte[] bytes = new byte[32]; // 256-bit CSPRNG
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String newTicketToken() {
        return newOpaqueToken();
    }

    public static String newIdempotencyKeyHint() {
        return UUID.randomUUID().toString();
    }

    private static JsonNode sort(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            ObjectNode out = new ObjectMapper().getNodeFactory().objectNode();
            obj.propertyNames().stream().sorted(Comparator.naturalOrder())
                    .forEach(name -> {
                        JsonNode child = obj.get(name);
                        out.set(name, child instanceof ObjectNode || child instanceof ArrayNode ? sort(child)
                                : normalizeLeaf(child));
                    });
            return out;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode out = new ObjectMapper().getNodeFactory().arrayNode();
            arr.forEach(child -> out.add(child instanceof ObjectNode || child instanceof ArrayNode ? sort(child)
                    : normalizeLeaf(child)));
            return out;
        }
        return normalizeLeaf(node);
    }

    private static JsonNode normalizeLeaf(JsonNode node) {
        if (node.isTextual()) {
            String normalized = Normalizer.normalize(node.stringValue(), Normalizer.Form.NFC);
            return new ObjectMapper().getNodeFactory().textNode(normalized);
        }
        if (node.isNumber() && !node.isIntegralNumber()) {
            // 10.00 and 10.0 must hash identically.
            BigDecimal decimal = node.decimalValue().stripTrailingZeros();
            return new ObjectMapper().getNodeFactory().numberNode(decimal);
        }
        return node;
    }

    private static String digestHex(String algorithm, byte[] input) {
        try {
            return hex(MessageDigest.getInstance(algorithm).digest(input));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
