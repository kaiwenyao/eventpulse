package dev.kaiwen.eventpulse.common;

/**
 * 当前登录用户，存在 ThreadLocal 里。拦截器写入，请求结束后清掉。
 * 和 firmament 的 BaseContext 用法相同。
 */
public final class BaseContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private BaseContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static void setRole(String role) {
        ROLE.set(role);
    }

    public static String getRole() {
        return ROLE.get();
    }

    public static void clear() {
        USER_ID.remove();
        ROLE.remove();
    }
}
