package dev.kaiwen.eventpulse;

import java.util.List;
import java.util.UUID;

import dev.kaiwen.eventpulse.recs.EmbeddingService;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** EmbeddingService: pgvector detection, deterministic hashing, write path. */
class EmbeddingServiceTest {

    private JdbcTemplate jdbc;

    private EmbeddingService service(boolean vectorAvailable) {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(vectorAvailable ? 1 : 0);
        return new EmbeddingService(jdbc);
    }

    @Test
    void embedderProducesDeterministicUnitVectors() {
        EmbeddingService svc = service(true);
        double[] a = svc.embed("独立摇滚 music night");
        double[] b = svc.embed("独立摇滚 music night");
        assertThat(a).containsExactly(b);
        double norm = 0;
        for (double v : a) {
            norm += v * v;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        // empty text -> zero vector (still safe to store)
        assertThat(svc.embed("")).hasSize(64).containsOnly(0.0);
    }

    @Test
    void embedEventWritesVectorWhenPgvectorAvailable() {
        EmbeddingService svc = service(true);
        svc.embedEvent(UUID.randomUUID(), "城市光影", "art", "数字艺术展");
        verify(jdbc).update(contains("embedding = ?::vector"), any(Object[].class));
    }

    @Test
    void embedEventIsNoOpWithoutPgvector() {
        EmbeddingService svc = service(false);
        svc.embedEvent(UUID.randomUUID(), "城市光影", "art", "x");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
