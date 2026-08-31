package com.eventpulse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eventpulse.auth.AuthService;
import com.eventpulse.booking.BookingDtos;
import com.eventpulse.booking.BookingService;
import com.eventpulse.booking.IdempotencyService;
import com.eventpulse.common.error.ApiException;
import com.eventpulse.common.error.ErrorCode;
import com.eventpulse.common.error.GlobalExceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exception handlers: idempotent replay, in-progress, validation, fallbacks. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void apiExceptionMapsToItsOwnStatusAndCode() {
        ResponseEntity<?> response = handler.handleApi(new ApiException(ErrorCode.INSUFFICIENT_INVENTORY,
                "no stock", Map.of("tierId", "only 2 left")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var body = (com.eventpulse.common.error.ApiError) response.getBody();
        assertThat(body.code()).isEqualTo("INSUFFICIENT_INVENTORY");
        assertThat(body.fieldErrors()).containsEntry("tierId", "only 2 left");
        assertThat(body.timestamp()).isNotNull();
    }

    @Test
    void idempotentReplayReturnsStoredStatusAndBody() {
        IdempotencyService.IdempotentReplay replay =
                new IdempotencyService.IdempotentReplay(201, Map.of("id", "same"));
        ResponseEntity<?> response = handler.handleReplay(replay);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo(Map.of("id", "same"));
    }

    @Test
    void idempotencyInProgressReturns202WithRetryAfter() {
        ResponseEntity<?> response = handler.handleInProgress(
                new IdempotencyService.IdempotencyInProgress());
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void validationHandlersProduceFieldErrors() {
        org.springframework.validation.BindingResult binding = mock(org.springframework.validation.BindingResult.class);
        org.springframework.validation.FieldError error =
                new org.springframework.validation.FieldError("obj", "quantity", "must be positive");
        when(binding.getFieldErrors()).thenReturn(List.of(error));
        MethodArgumentNotValidException invalid =
                new MethodArgumentNotValidException(null, binding);
        ResponseEntity<?> validation = handler.handleValidation(invalid);
        assertThat(validation.getStatusCode().value()).isEqualTo(400);
        assertThat(((com.eventpulse.common.error.ApiError) validation.getBody()).fieldErrors())
                .containsEntry("quantity", "must be positive");

        MethodArgumentTypeMismatchException mismatch = new MethodArgumentTypeMismatchException("x",
                UUID.class, "id", null, new IllegalArgumentException("bad uuid"));
        ResponseEntity<?> typeMismatch = handler.handleTypeMismatch(mismatch);
        assertThat(typeMismatch.getStatusCode().value()).isEqualTo(400);
        assertThat(((com.eventpulse.common.error.ApiError) typeMismatch.getBody()).message())
                .contains("id");

        ResponseEntity<?> unreadable = handler.handleUnreadable(
                new org.springframework.http.converter.HttpMessageNotReadableException("bad json",
                        (org.springframework.http.HttpInputMessage) null));
        assertThat(unreadable.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<?> noResource = handler.handleNoResource(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "/missing", null));
        assertThat(noResource.getStatusCode().value()).isEqualTo(404);

        ResponseEntity<?> generic = handler.handleGeneric(new IllegalStateException("boom"));
        assertThat(generic.getStatusCode().value()).isEqualTo(500);
        assertThat(((com.eventpulse.common.error.ApiError) generic.getBody()).code()).isEqualTo("INTERNAL");
    }
}
