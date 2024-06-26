/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.internal.recorder;

import brave.Clock;
import brave.GarbageCollectors;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.internal.InternalPropagation;
import brave.internal.Nullable;
import brave.internal.handler.OrphanTracker;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.test.TestSpanHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static brave.internal.InternalPropagation.FLAG_LOCAL_ROOT;
import static brave.internal.InternalPropagation.FLAG_SAMPLED;
import static brave.internal.InternalPropagation.FLAG_SAMPLED_SET;
import static org.assertj.core.api.Assertions.assertThat;

class PendingSpansTest {
  static {
    String unused = SamplingFlags.DEBUG.toString(); // ensure InternalPropagation is wired for tests
  }

  List<TraceContext> contexts = new ArrayList<>();
  TestSpanHandler spans = new TestSpanHandler();
  // PendingSpans should always be passed a trace context instantiated by the Tracer. This fakes
  // a local root span, so that we don't have to depend on the Tracer to run these tests.
  TraceContext context = InternalPropagation.instance.newTraceContext(
    FLAG_SAMPLED_SET | FLAG_SAMPLED | FLAG_LOCAL_ROOT,
    0L,
    1L,
    2L,
    0L,
    1L,
    Collections.emptyList()
  );
  AtomicInteger clock = new AtomicInteger();
  PendingSpans pendingSpans;

  @BeforeEach void init() {
    MutableSpan defaultSpan = new MutableSpan();
    defaultSpan.localServiceName("favistar");
    defaultSpan.localIp("1.2.3.4");
    Clock clock = () -> this.clock.incrementAndGet() * 1000L;
    SpanHandler orphanTracker =
        OrphanTracker.newBuilder().defaultSpan(defaultSpan).clock(clock).build();
    pendingSpans = new PendingSpans(defaultSpan, clock, new SpanHandler() {
      @Override
      public boolean begin(TraceContext ctx, MutableSpan span, @Nullable TraceContext parent) {
        contexts.add(ctx);
        return orphanTracker.begin(ctx, span, parent);
      }

      @Override public boolean end(TraceContext ctx, MutableSpan span, Cause cause) {
        orphanTracker.end(ctx, span, cause);
        spans.end(ctx, span, cause);
        return true;
      }
    }, new AtomicBoolean());
  }

  @Test void getOrCreate_lazyCreatesASpan() {
    PendingSpan span = pendingSpans.getOrCreate(null, context, false);

    assertThat(span).isNotNull();
  }

  /** Ensure we use the same clock for traces that started in-process */
  @Test void getOrCreate_reusesClockFromParent() {
    TraceContext trace = context;
    TraceContext traceJoin = trace.toBuilder().shared(true).build();
    TraceContext trace2 = context.toBuilder().traceId(2L).build();
    TraceContext traceChild =
      TraceContext.newBuilder().traceId(1L).parentId(trace.spanId()).spanId(3L).build();

    PendingSpan traceSpan = pendingSpans.getOrCreate(null, trace, false);
    PendingSpan traceJoinSpan = pendingSpans.getOrCreate(trace, traceJoin, false);
    PendingSpan trace2Span = pendingSpans.getOrCreate(null, trace2, false);
    PendingSpan traceChildSpan = pendingSpans.getOrCreate(trace, traceChild, false);

    assertThat(traceSpan.clock).isSameAs(traceChildSpan.clock);
    assertThat(traceSpan.clock).isSameAs(traceJoinSpan.clock);
    assertThat(traceSpan.clock).isNotSameAs(trace2Span.clock);
  }

  @Test void getOrCreate_cachesReference() {
    PendingSpan span = pendingSpans.getOrCreate(null, context, false);
    assertThat(pendingSpans.getOrCreate(null, context, false)).isSameAs(span);
  }

  @Test void getOrCreate_splitsSharedServerDataFromClient() {
    TraceContext context2 = context.toBuilder().shared(true).build();

    assertThat(pendingSpans.getOrCreate(null, context, false)).isNotEqualTo(
      pendingSpans.getOrCreate(null, context2, false));
  }

  @Test void remove_doesntReport() {
    pendingSpans.getOrCreate(null, context, false);
    pendingSpans.remove(context);

    assertThat(spans).isEmpty();
  }

  /**
   * This is the key feature. Spans orphaned via GC are reported to zipkin on the next action.
   *
   * <p>This is a customized version of https://github.com/raphw/weak-lock-free/blob/master/src/test/java/com/blogspot/mydailyjava/weaklockfree/WeakConcurrentMapTest.java
   */
  @Test void reportOrphanedSpans_afterGC() {
    TraceContext context1 = context.toBuilder().traceId(1).spanId(1).build();
    PendingSpan span = pendingSpans.getOrCreate(null, context1, false);
    span.span.tag("foo", "bar");
    span.span.tag("ice", "melt");

    // Prove that copy constructor doesn't pin GC
    MutableSpan copyOfData = new MutableSpan(span.span);
    span = null; // clear reference so GC occurs

    TraceContext context2 = context.toBuilder().traceId(2).spanId(2).build();
    pendingSpans.getOrCreate(null, context2, false);
    TraceContext context3 = context.toBuilder().traceId(3).spanId(3).build();
    pendingSpans.getOrCreate(null, context3, false);
    TraceContext context4 = context.toBuilder().traceId(4).spanId(4).build();
    pendingSpans.getOrCreate(null, context4, false);
    // ensure sampled local spans are not reported when orphaned unless they are also sampled remote
    TraceContext context5 =
      context.toBuilder().spanId(5).sampledLocal(true).sampled(false).build();
    pendingSpans.getOrCreate(null, context5, false);

    int initialClockVal = clock.get();

    // By clearing strong references in this test, we are left with the weak ones in the map
    context1 = context2 = null;
    GarbageCollectors.blockOnGC();

    pendingSpans.expungeStaleEntries();

    assertThat(spans).hasSize(2);
    // orphaned without data
    assertThat(spans.get(0).id()).isEqualTo("0000000000000002");
    assertThat(spans.get(0).containsAnnotation("brave.flush")).isFalse();

    // orphaned with data
    assertThat(spans.get(1).id()).isEqualTo("0000000000000001");
    assertThat(spans.get(1).tags()).hasSize(2); // data was flushed
    assertThat(spans.get(1).containsAnnotation("brave.flush")).isTrue();
  }

  @Test void noop_afterGC() {
    TraceContext context1 = context.toBuilder().spanId(1).build();
    pendingSpans.getOrCreate(null, context1, false);
    TraceContext context2 = context.toBuilder().spanId(2).build();
    pendingSpans.getOrCreate(null, context2, false);
    TraceContext context3 = context.toBuilder().spanId(3).build();
    pendingSpans.getOrCreate(null, context3, false);
    TraceContext context4 = context.toBuilder().spanId(4).build();
    pendingSpans.getOrCreate(null, context4, false);

    int initialClockVal = clock.get();

    pendingSpans.noop.set(true);

    // By clearing strong references in this test, we are left with the weak ones in the map
    context1 = context2 = null;

    pendingSpans.expungeStaleEntries();

    // since this is noop, we don't expect any spans to be reported
    assertThat(spans).isEmpty();

    // we also expect the clock to not have been called
    assertThat(clock.get()).isEqualTo(initialClockVal);
  }

  @Test void orphanContext_dropsExtra() {
    TraceContext context1 = context.toBuilder().addExtra(1).addExtra(true).build();
    TraceContext context = this.context.toBuilder().build();
    pendingSpans.getOrCreate(null, context, false).state().tag("foo", "bar");
    // We drop the reference to the context, which means the next GC should attempt to flush it
    context = null;

    GarbageCollectors.blockOnGC();
    pendingSpans.expungeStaleEntries();

    assertThat(contexts).hasSize(1);
    assertThat(contexts.get(0)).isEqualTo(context1); // ID comparision is the same
    assertThat(contexts.get(0).extra()).isEmpty(); // No context decorations are retained
  }

  @Test void orphanContext_includesAllFlags() {
    TraceContext context1 =
      context.toBuilder().sampled(null).sampledLocal(true).shared(true).build();
    TraceContext context = context1.toBuilder().build();
    pendingSpans.getOrCreate(null, context, false).state().tag("foo", "bar");
    // We drop the reference to the context, which means the next GC should attempt to flush it
    context = null;

    GarbageCollectors.blockOnGC();
    pendingSpans.expungeStaleEntries();

    assertThat(contexts).hasSize(1);
    assertThat(InternalPropagation.instance.flags(contexts.get(0)))
      .isEqualTo(InternalPropagation.instance.flags(context1)); // no flags lost
  }
}
