package dev.kaiwen.eventpulse.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class RequestLoggingInterceptorTest {

    private final RequestLoggingInterceptor interceptor = new RequestLoggingInterceptor();
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Logger interceptorLogger;

    @BeforeEach
    void setUp() {
        MDC.clear();
        interceptorLogger = (Logger) LoggerFactory.getLogger(RequestLoggingInterceptor.class);
        events.list.clear();
        events.start();
        interceptorLogger.addAppender(events);
    }

    @AfterEach
    void tearDown() {
        interceptorLogger.detachAppender(events);
        events.stop();
        MDC.clear();
    }

    @Test
    void preHandleLogsIncomingRequestAndSetsRequestId() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/events");
        request.setQueryString("city=london");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(request.getAttribute(RequestLoggingInterceptor.START_NS)).isInstanceOf(Long.class);
        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isNotBlank();
        assertThat(events.list).hasSize(1);
        assertThat(events.list.get(0).getFormattedMessage())
                .isEqualTo("request started GET /api/events?city=london");
    }

    @Test
    void afterCompletionLogsSuccessAndClearsMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bookings");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isNull();
        assertThat(events.list).hasSize(2);
        assertThat(events.list.get(1).getLevel()).isEqualTo(Level.INFO);
        assertThat(events.list.get(1).getFormattedMessage()).contains("request completed", "status=200");
    }

    @Test
    void afterCompletionLogsFailureAndClearsMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/events/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), new IllegalStateException("boom"));

        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isNull();
        assertThat(events.list).hasSize(2);
        assertThat(events.list.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.list.get(1).getFormattedMessage())
                .contains("request failed", "status=500", "error=java.lang.IllegalStateException: boom");
    }

    @Test
    void serverErrorWithoutExceptionIsStillLoggedAsFailed() {
        // @ExceptionHandler 接住异常后 afterCompletion 拿到的 ex 是 null，
        // 5xx 只能靠响应状态识别为失败。
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/events");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(503);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(events.list).hasSize(2);
        assertThat(events.list.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.list.get(1).getFormattedMessage())
                .contains("request failed", "status=503", "error=-");
    }

    @Test
    void businessErrorStaysAtInfo() {
        // 4xx 是正常业务流量（登录过期、参数校验失败），不打 WARN。
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/events/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(404);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(events.list).hasSize(2);
        assertThat(events.list.get(1).getLevel()).isEqualTo(Level.INFO);
        assertThat(events.list.get(1).getFormattedMessage()).contains("request completed", "status=404");
    }

    @Test
    void asyncRedispatchReusesOriginalRequestIdAndStart() {
        // SSE 异步结束时的 ASYNC 再分派会重跑 preHandle：
        // requestId 与起始时间必须沿用首进的，started 也不能打第二条。
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/bookings/1/events");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        String firstRequestId = MDC.get(RequestLoggingInterceptor.REQUEST_ID);
        Long firstStartNs = (Long) request.getAttribute(RequestLoggingInterceptor.START_NS);
        assertThat(events.list).hasSize(1);

        interceptor.afterConcurrentHandlingStarted(request, response, new Object());
        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isNull();

        interceptor.preHandle(request, response, new Object());

        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isEqualTo(firstRequestId);
        assertThat(request.getAttribute(RequestLoggingInterceptor.START_NS)).isEqualTo(firstStartNs);
        assertThat(events.list).hasSize(1);

        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isNull();
        ILoggingEvent completed = events.list.get(1);
        assertThat(completed.getFormattedMessage()).startsWith("request completed");
        assertThat(completed.getMDCPropertyMap().get(RequestLoggingInterceptor.REQUEST_ID))
                .isEqualTo(firstRequestId);
    }

    @Test
    void afterCompletionWithoutStartAttributeUsesUnknownDuration() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/events");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        MDC.put(RequestLoggingInterceptor.REQUEST_ID, "stale-id");

        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(MDC.get(RequestLoggingInterceptor.REQUEST_ID)).isNull();
        assertThat(events.list).hasSize(1);
        assertThat(events.list.get(0).getFormattedMessage()).contains("durationMs=-1");
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

        assertThat(events.list).hasSize(2);
        assertThat(events.list.get(0).getFormattedMessage()).isEqualTo("request started GET /api/events");
        assertThat(events.list.get(1).getFormattedMessage()).startsWith("request completed GET /api/events ");
    }
}