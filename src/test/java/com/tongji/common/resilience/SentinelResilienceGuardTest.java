package com.tongji.common.resilience;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SentinelResilienceGuardTest {

    private static final String RESOURCE = "resilience:test";

    private final RecordingTracer tracer = new RecordingTracer();
    private final ResilienceGuard guard = new SentinelResilienceGuard(tracer);

    @AfterEach
    void tearDown() {
        FlowRuleManager.loadRules(List.of());
    }

    @Test
    void returnsOperationResultWhenGuardedCallSucceeds() {
        GuardResult<String> result = guard.execute(
                RESOURCE,
                () -> "ok",
                () -> "fallback",
                throwable -> !(throwable instanceof BusinessException)
        );

        assertThat(result.value()).isEqualTo("ok");
        assertThat(result.fallbackApplied()).isFalse();
        assertThat(result.failure()).isNull();
    }

    @Test
    void returnsFallbackWhenSentinelBlocksTheResource() {
        FlowRule rule = new FlowRule();
        rule.setResource(RESOURCE);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);
        FlowRuleManager.loadRules(List.of(rule));

        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);

        GuardResult<String> result = guard.execute(
                RESOURCE,
                () -> "ok",
                () -> {
                    fallbackInvoked.set(true);
                    return "fallback";
                },
                throwable -> !(throwable instanceof BusinessException)
        );

        assertThat(fallbackInvoked).isTrue();
        assertThat(result.value()).isEqualTo("fallback");
        assertThat(result.fallbackApplied()).isTrue();
        assertThat(result.failure()).isInstanceOf(BlockException.class);
        assertThat(tracer.traceCount()).isEqualTo(1);
    }

    @Test
    void consultsClassifierAndDoesNotTraceBusinessExceptionAsSystemFailure() {
        BusinessException businessException = new BusinessException(ErrorCode.BAD_REQUEST, "bad request");
        AtomicBoolean classifierConsulted = new AtomicBoolean(false);

        GuardResult<String> result = guard.execute(
                RESOURCE,
                () -> {
                    throw businessException;
                },
                () -> "fallback",
                throwable -> {
                    classifierConsulted.set(true);
                    return !(throwable instanceof BusinessException);
                }
        );

        assertThat(classifierConsulted).isTrue();
        assertThat(result.value()).isEqualTo("fallback");
        assertThat(result.fallbackApplied()).isTrue();
        assertThat(result.failure()).isSameAs(businessException);
        assertThat(tracer.traceCount()).isZero();
    }

    @Test
    void letsFatalErrorEscapeWithoutFallback() {
        AssertionError fatalError = new AssertionError("fatal");
        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);

        assertThatThrownBy(() -> guard.execute(
                RESOURCE,
                () -> {
                    throw fatalError;
                },
                () -> {
                    fallbackInvoked.set(true);
                    return "fallback";
                },
                throwable -> true
        )).isSameAs(fatalError);

        assertThat(fallbackInvoked).isFalse();
        assertThat(tracer.traceCount()).isZero();
    }

    private static final class RecordingTracer implements SentinelResilienceGuard.SentinelTracer {
        private final AtomicInteger traceCount = new AtomicInteger();

        @Override
        public void trace(Throwable throwable) {
            traceCount.incrementAndGet();
        }

        int traceCount() {
            return traceCount.get();
        }
    }
}
