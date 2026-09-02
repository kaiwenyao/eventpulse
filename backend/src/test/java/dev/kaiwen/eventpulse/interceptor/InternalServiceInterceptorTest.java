package dev.kaiwen.eventpulse.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.service.JwtService;

class InternalServiceInterceptorTest {

    private AppProperties properties;
    private InternalServiceInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getAi().setInternalToken("internal-secret-token");
        interceptor = new InternalServiceInterceptor(properties, new JwtService(properties));
    }

    @AfterEach
    void clear() {
        BaseContext.clear();
    }

    @Test
    void missingOrWrongServiceTokenIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/ai-tools/events/popular");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);

        request.addHeader(InternalServiceInterceptor.INTERNAL_TOKEN_HEADER, "wrong");
        MockHttpServletResponse response2 = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response2, new Object())).isFalse();
        assertThat(response2.getStatus()).isEqualTo(401);

        // 空 header 也必须拒绝：否则凭证被配成空串时 "" == "" 恒真，直接放行。
        request.addHeader(InternalServiceInterceptor.INTERNAL_TOKEN_HEADER, "");
        MockHttpServletResponse response3 = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response3, new Object())).isFalse();
        assertThat(response3.getStatus()).isEqualTo(401);
    }

    @Test
    void blankInternalTokenFailsAtStartup() {
        properties.getAi().setInternalToken("");
        InternalServiceInterceptor misconfigured = new InternalServiceInterceptor(properties, new JwtService(properties));
        assertThatThrownBy(misconfigured::validateConfiguration).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validTokenWithoutUserContextPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/ai-tools/events/popular");
        request.addHeader(InternalServiceInterceptor.INTERNAL_TOKEN_HEADER, "internal-secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(BaseContext.getUserId()).isNull();
    }

    @Test
    void signedUserContextIsAppliedAndInvalidOnesRejected() throws Exception {
        JwtService jwt = new JwtService(properties);
        String context = jwt.createContextToken(42L, "USER", "req-1", 300);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/ai-tools/users/me/preferences");
        request.addHeader(InternalServiceInterceptor.INTERNAL_TOKEN_HEADER, "internal-secret-token");
        request.addHeader(InternalServiceInterceptor.USER_CONTEXT_HEADER, context);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(BaseContext.getUserId()).isEqualTo(42L);
        assertThat(BaseContext.getRole()).isEqualTo("USER");

        // 普通登录 JWT 不能冒充用户上下文（purpose 校验）。
        String loginToken = jwt.createToken(42L, "USER");
        MockHttpServletRequest forged = new MockHttpServletRequest("GET", "/internal/ai-tools/users/me/preferences");
        forged.addHeader(InternalServiceInterceptor.INTERNAL_TOKEN_HEADER, "internal-secret-token");
        forged.addHeader(InternalServiceInterceptor.USER_CONTEXT_HEADER, loginToken);
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(forged, rejected, new Object())).isFalse();
        assertThat(rejected.getStatus()).isEqualTo(401);
        BaseContext.clear();
        assertThat(BaseContext.getUserId()).isNull();
    }

    @Test
    void afterCompletionClearsUserContext() throws Exception {
        BaseContext.setUserId(1L);
        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);
        assertThat(BaseContext.getUserId()).isNull();
    }
}
