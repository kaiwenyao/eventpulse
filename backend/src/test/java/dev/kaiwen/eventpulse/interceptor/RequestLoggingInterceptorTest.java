package dev.kaiwen.eventpulse.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingInterceptorTest {

    private final RequestLoggingInterceptor interceptor = new RequestLoggingInterceptor();

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void preHandleLogsIncomingRequestAndSetsRequestId() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/events");
        request.setQueryString("city=london");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(request.getAttribute(RequestLoggingInterceptor.START_NS)).isInstanceOf(Long.class);
        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isNotBlank();
    }

    @Test
    void afterCompletionLogsSuccessAndClearsMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bookings");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isNull();
    }

    @Test
    void afterCompletionLogsFailureAndClearsMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/events/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), new IllegalStateException("boom"));

        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isNull();
    }

    @Test
    void afterCompletionWithoutStartAttributeUsesUnknownDuration() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/events");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        MDC.put(RequestLoggingInterceptor.REQUEST_ID, "stale-id");

        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isNull();
    }

    @Test
    void afterConcurrentHandlingStartedClearsMdcForReusedThread() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/bookings/1/events");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isNotBlank();

        interceptor.afterConcurrentHandlingStarted(request, new MockHttpServletResponse(), new Object());
        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isNull();
    }

    @Test
    void blankQueryStringIsOmittedFromPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/events");
        request.setQueryString("   ");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
    }
}
