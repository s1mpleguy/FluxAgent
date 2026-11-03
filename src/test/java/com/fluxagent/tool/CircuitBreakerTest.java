package com.fluxagent.tool;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {
    @Test
    void permitsOnlyOneRecoveryProbe() {
        var clock = new ToolExecutorTest.MutableClock();
        var breaker = new CircuitBreaker(2, Duration.ofSeconds(10), clock);
        breaker.recordFailure(breaker.acquire());
        breaker.recordFailure(breaker.acquire());
        assertThat(breaker.acquire()).isNull();
        clock.advance(Duration.ofSeconds(10));
        var failedProbe = breaker.acquire();
        assertThat(failedProbe).isNotNull();
        assertThat(breaker.acquire()).isNull();
        breaker.recordFailure(failedProbe);
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        clock.advance(Duration.ofSeconds(10));
        var successfulProbe = breaker.acquire();
        assertThat(successfulProbe).isNotNull();
        breaker.recordSuccess(successfulProbe);
        assertThat(breaker.acquire()).isNotNull();
    }
}
