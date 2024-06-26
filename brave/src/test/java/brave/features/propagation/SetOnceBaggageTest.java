/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.features.propagation;

import brave.Tags;
import brave.Tracer;
import brave.Tracing;
import brave.baggage.BaggageField;
import brave.baggage.BaggagePropagationConfig.SingleBaggageField;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.internal.Nullable;
import brave.propagation.B3Propagation;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.test.TestSpanHandler;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static brave.baggage.BaggagePropagation.newFactoryBuilder;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This shows how to compute a value for Baggage only once per trace, by checking its value existed
 * first. Notably, this is at the lowest abstraction: {@link SpanHandler} has no reliance on HTTP or
 * otherwise.
 */
class SetOnceBaggageTest {
  static final BaggageField EPOCH_SECONDS = BaggageField.create("epoch_seconds");

  static final class RootOnlyBaggage extends SpanHandler {
    @Override
    public boolean begin(TraceContext context, MutableSpan span, @Nullable TraceContext parent) {
      if (EPOCH_SECONDS.getValue(context) == null) { // only set at the first span
        long epochSeconds = System.currentTimeMillis() / 1000;
        sleepSlightlyOverASecond();
        EPOCH_SECONDS.updateValue(context, String.valueOf(epochSeconds));
      }
      return true;
    }

    @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
      Tags.BAGGAGE_FIELD.tag(EPOCH_SECONDS, context, span);
      return true;
    }
  }

  TestSpanHandler spans = new TestSpanHandler();
  Propagation.Factory propagationFactory = newFactoryBuilder(B3Propagation.FACTORY)
      .add(SingleBaggageField.remote(EPOCH_SECONDS)).build();
  Tracing tracing = Tracing.newBuilder()
      .propagationFactory(propagationFactory)
      .addSpanHandler(new RootOnlyBaggage())
      .addSpanHandler(spans)
      .build();
  Tracer tracer = tracing.tracer();

  @AfterEach void after() {
    tracing.close();
  }

  @Test void countChildren() {
    brave.Span root1 = tracer.newTrace().name("root1").start();
    brave.Span root2 = tracer.newTrace().name("root2").start();
    brave.Span root1Child1 = tracer.newChild(root1.context()).name("root1Child1").start();
    brave.Span root1Child1Child1 =
        tracer.newChild(root1Child1.context()).name("root1Child1Child1").start();
    brave.Span root2Child1 = tracer.newChild(root2.context()).name("root2Child1").start();
    brave.Span root1Child1Child2 =
        tracer.newChild(root1Child1.context()).name("root1Child1Child2").start();
    brave.Span root1Child1Child2Child1 =
        tracer.newChild(root1Child1Child1.context()).name("root1Child1Child2Child1").start();
    root1Child1Child2Child1.finish();
    root2Child1.finish();
    root1Child1Child1.finish();
    root2.finish();
    root1Child1Child2.finish();
    root1Child1.finish();
    root1.finish();

    Set<String> distinct_epoch_seconds = spans.spans().stream()
        .map(s -> s.tags().get("epoch_seconds"))
        .collect(Collectors.toSet());

    assertThat(distinct_epoch_seconds).hasSize(2); // 2 root spans!
  }

  static void sleepSlightlyOverASecond() {
    try {
      Thread.sleep(1001);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
