package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.springframework.http.MediaType;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 测试替身：实现包级私有的 {@link ResponseBodyEmitter.Handler}（测试源码
 * 与框架同包才能访问）。complete() 时触发 completion 回调（与真实容器一致）；
 * {@link #broken()} 构造的实例在 send 时抛 IOException，模拟客户端断开；
 * {@link #failingOn(Class, RuntimeException)} 构造的实例只对携带指定类型
 * 数据的帧抛 RuntimeException，模拟该帧序列化失败（如 done 帧的 Jackson
 * 出错），其余帧正常送达。
 *
 * 测试里用 emitter.initialize(handler) 手动接上，模拟 MVC 完成的初始化。
 */
public final class CapturingEmitterHandler implements ResponseBodyEmitter.Handler {

    /** 流式帧由异步线程写入，断言线程并发读取，故用写时复制列表避免 ConcurrentModificationException。 */
    private final List<Object> sent = new CopyOnWriteArrayList<>();
    private final boolean broken;
    private final Class<?> failOnType;
    private final RuntimeException failOnTypeFailure;
    private Runnable onCompletion;
    private Consumer<Throwable> onError;
    private Runnable onTimeout;

    public CapturingEmitterHandler() {
        this(false);
    }

    private CapturingEmitterHandler(boolean broken) {
        this(broken, null, null);
    }

    private CapturingEmitterHandler(boolean broken, Class<?> failOnType, RuntimeException failOnTypeFailure) {
        this.broken = broken;
        this.failOnType = failOnType;
        this.failOnTypeFailure = failOnTypeFailure;
    }

    public static CapturingEmitterHandler broken() {
        return new CapturingEmitterHandler(true);
    }

    /** 只对携带指定类型数据的帧抛 RuntimeException（该帧发不出去），其余帧正常。 */
    public static CapturingEmitterHandler failingOn(Class<?> dataType, RuntimeException failure) {
        return new CapturingEmitterHandler(false, dataType, failure);
    }

    /** 与 MVC 容器等价：把 emitter 绑定到本 Handler（initialize 是包级私有）。 */
    public void attachTo(SseEmitter emitter) {
        try {
            emitter.initialize(this);
        }
        catch (IOException e) {
            throw new IllegalStateException("emitter init failed", e);
        }
    }

    public List<Object> received() {
        return sent;
    }

    @Override
    public void send(Object data, MediaType mediaType) throws IOException {
        if (broken) {
            throw new IOException("client gone");
        }
        if (failOnType != null && failOnType.isInstance(data)) {
            throw failOnTypeFailure;
        }
        sent.add(data);
    }

    @Override
    public void send(java.util.Set<ResponseBodyEmitter.DataWithMediaType> data) throws IOException {
        if (broken) {
            throw new IOException("client gone");
        }
        if (data.stream().anyMatch(item -> failOnType != null && failOnType.isInstance(item.getData()))) {
            throw failOnTypeFailure;
        }
        data.forEach(item -> sent.add(item.getData()));
    }

    @Override
    public void complete() {
        if (onCompletion != null) {
            onCompletion.run();
        }
    }

    @Override
    public void completeWithError(Throwable t) {
        if (onError != null) {
            onError.accept(t);
        }
        if (onCompletion != null) {
            onCompletion.run();
        }
    }

    @Override
    public void onTimeout(Runnable callback) {
        this.onTimeout = callback;
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
        this.onError = callback;
    }

    @Override
    public void onCompletion(Runnable callback) {
        this.onCompletion = callback;
    }
}
