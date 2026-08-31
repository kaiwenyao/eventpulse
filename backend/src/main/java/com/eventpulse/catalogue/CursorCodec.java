package com.eventpulse.catalogue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import com.eventpulse.common.AppProperties;
import com.eventpulse.common.CanonicalJson;
import com.eventpulse.common.error.ApiException;
import com.eventpulse.common.error.ErrorCode;
import com.eventpulse.outbox.OutboxJson;

import org.springframework.stereotype.Component;

/**
 * Signed keyset cursor codec. Tampering, version drift and expiry are all
 * rejected; the HMAC prevents clients from forging queryAsOf or the boundary.
 */
@Component
public class CursorCodec {

    private final AppProperties properties;

    public CursorCodec(AppProperties properties) {
        this.properties = properties;
    }

    public String encode(SearchCursor cursor) {
        String json = OutboxJson.write(cursor);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
        String signature = CanonicalJson.hmacSha256Hex(properties.security().secretKey(), body);
        return body + "." + signature;
    }

    public SearchCursor decode(String cursor) {
        if (cursor == null || !cursor.contains(".")) {
            throw new ApiException(ErrorCode.CURSOR_INVALID, "malformed cursor");
        }
        String[] parts = cursor.split("\\.", 2);
        String expected = CanonicalJson.hmacSha256Hex(properties.security().secretKey(), parts[0]);
        if (!expected.equals(parts[1])) {
            throw new ApiException(ErrorCode.CURSOR_INVALID, "cursor signature invalid");
        }
        SearchCursor decoded;
        try {
            String json = new String(Base64.getUrlDecoder().decode(parts[0]));
            decoded = OutboxJson.mapper().readValue(json, SearchCursor.class);
        }
        catch (Exception e) {
            throw new ApiException(ErrorCode.CURSOR_INVALID, "cursor unreadable");
        }
        if (decoded == null || decoded.version() != SearchCursor.CURRENT_VERSION) {
            throw new ApiException(ErrorCode.CURSOR_INVALID, "cursor version unsupported");
        }
        if (decoded.expiresAt() != null && decoded.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.CURSOR_EXPIRED, "cursor expired, restart from page one");
        }
        return decoded;
    }

    public Instant newExpiry() {
        return Instant.now().plus(15, ChronoUnit.MINUTES);
    }
}
