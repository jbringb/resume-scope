package dev.jbringb.resume_scope.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jbringb.resume_scope.api.dto.AnalysisRunResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnalysisEventBusTest {

    // Regression guard: Sinks.many().multicast().onBackpressureBuffer() defaults to autoCancel=true,
    // which permanently kills the sink the first time its subscriber count returns to zero — exactly
    // what happens on the normal happy path (an SSE client's takeUntil(terminal) completing cancels
    // its subscription once a run finishes). Without autoCancel=false, this test fails: the second
    // subscriber gets zero events and immediate onComplete().
    @Test
    void stream_survivesSubscriberCountReturningToZero() throws InterruptedException {
        var eventBus = new AnalysisEventBus();
        var runId = UUID.randomUUID();

        var firstReceived = new AtomicInteger();
        var first = eventBus.stream().subscribe(e -> firstReceived.incrementAndGet());
        eventBus.publish(event(runId, "RUNNING"));
        Thread.sleep(50);
        assertThat(firstReceived.get()).isEqualTo(1);

        // Subscriber count returns to zero.
        first.dispose();
        Thread.sleep(50);

        // A brand-new SSE request (e.g. for a different, later run) must still receive live events.
        var secondReceived = new AtomicInteger();
        var secondCompleted = new AtomicBoolean();
        var second = eventBus.stream()
                .subscribe(e -> secondReceived.incrementAndGet(), err -> {}, () -> secondCompleted.set(true));
        eventBus.publish(event(runId, "COMPLETED"));
        Thread.sleep(50);

        assertThat(secondReceived.get()).isEqualTo(1);
        assertThat(secondCompleted.get()).isFalse();

        second.dispose();
    }

    private static AnalysisRunResponse event(UUID runId, String status) {
        return new AnalysisRunResponse(
                runId, UUID.randomUUID(), status, OffsetDateTime.now(), null, null, 0, 0, BigDecimal.ZERO);
    }
}
