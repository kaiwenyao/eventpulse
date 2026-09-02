package dev.kaiwen.eventpulse.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.service.JwtService;

/**
 * 复用同一个工作线程模拟「SSE 建立后线程回池，再处理下一个请求」：
 * afterConcurrentHandlingStarted 必须立即清掉 ThreadLocal，
 * 后续请求（含公共接口）不能读到上一个用户的身份。
 */
class JwtInterceptorAsyncTest {

    private JwtInterceptor interceptor;
    private ExecutorService reusedThread;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setSecretKey("test-secret-key-change-me-0123456789ab");
        interceptor = new JwtInterceptor(new JwtService(props));
        reusedThread = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        reusedThread.shutdownNow();
        BaseContext.clear();
    }

    @Test
    void reusedThreadDoesNotLeakUserIdentityAcrossRequests() throws Exception {
        Future<?> run = reusedThread.submit(() -> {
            try {
                // 请求 1：SSE 订阅建立，随后请求线程回到线程池。
                MockHttpServletRequest sseRequest = new MockHttpServletRequest("GET", "/api/bookings/1/events");
                sseRequest.addHeader("Authorization", "Bearer " + token(1L));
                MockHttpServletResponse response = new MockHttpServletResponse();
                assertThat(interceptor.preHandle(sseRequest, response, new Object())).isTrue();
                assertThat(BaseContext.getUserId()).isEqualTo(1L);

                interceptor.afterConcurrentHandlingStarted(sseRequest, response, new Object());
                // 异步处理已开始：原线程上不能保留用户身份。
                assertThat(BaseContext.getUserId()).isNull();
                assertThat(BaseContext.getRole()).isNull();

                // 同一个线程接着处理请求 2：读到的是新用户，不是残留的旧用户。
                MockHttpServletRequest next = new MockHttpServletRequest("POST", "/api/bookings");
                next.addHeader("Authorization", "Bearer " + token(2L));
                assertThat(interceptor.preHandle(next, new MockHttpServletResponse(), new Object())).isTrue();
                assertThat(BaseContext.getUserId()).isEqualTo(2L);

                interceptor.afterCompletion(next, new MockHttpServletResponse(), new Object(), null);
                assertThat(BaseContext.getUserId()).isNull();
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        run.get(5, TimeUnit.SECONDS);
    }

    @Test
    void preHandleClearsStaleContextEvenOnPublicPaths() throws Exception {
        Future<?> run = reusedThread.submit(() -> {
            BaseContext.setUserId(66L);
            BaseContext.setRole("USER");
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/events");
            try {
                assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
            // 公共接口本来不写上下文；preHandle 先清理一次保证不会读旧身份。
            assertThat(BaseContext.getUserId()).isNull();
            assertThat(BaseContext.getRole()).isNull();
        });
        run.get(5, TimeUnit.SECONDS);
    }

    private static String token(Long userId) {
        AppProperties props = new AppProperties();
        props.setSecretKey("test-secret-key-change-me-0123456789ab");
        return new JwtService(props).createToken(userId, "USER");
    }
}
