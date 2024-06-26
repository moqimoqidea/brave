/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.features.opentracing;

import brave.Tracing;
import brave.baggage.BaggageField;
import brave.baggage.BaggagePropagation;
import brave.baggage.BaggagePropagationConfig.SingleBaggageField;
import brave.propagation.B3Propagation;
import brave.propagation.TraceContext;
import brave.test.TestSpanHandler;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapAdapter;
import io.opentracing.tag.Tags;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;

/**
 * This shows how one might make an OpenTracing adapter for Brave, and how to navigate in and out of
 * the core concepts.
 */
class OpenTracingAdapterTest {
  static final BaggageField BAGGAGE_FIELD = BaggageField.create("userId");

  TestSpanHandler spans = new TestSpanHandler();
  Tracing brave = Tracing.newBuilder()
    .propagationFactory(BaggagePropagation.newFactoryBuilder(B3Propagation.FACTORY)
        .add(SingleBaggageField.newBuilder(BAGGAGE_FIELD)
            .addKeyName("user-id").build()).build())
    .addSpanHandler(spans).build();

  BraveTracer opentracing = BraveTracer.wrap(brave);

  @AfterEach void close() {
    brave.close();
  }

  @Test void startWithOpenTracingAndFinishWithBrave() {
    BraveSpan openTracingSpan = opentracing.buildSpan("encode")
      .withTag("lc", "codec")
      .withStartTimestamp(1L).start();

    brave.Span braveSpan = openTracingSpan.unwrap();

    braveSpan.annotate(2L, "pump fake");
    braveSpan.finish(3L);

    checkSpanReportedToZipkin();
  }

  @Test void startWithBraveAndFinishWithOpenTracing() {
    brave.Span braveSpan = brave.tracer().newTrace().name("encode")
      .tag("lc", "codec")
      .start(1L);

    io.opentracing.Span openTracingSpan = BraveSpan.wrap(braveSpan);

    openTracingSpan.log(2L, "pump fake");
    openTracingSpan.finish(3L);

    checkSpanReportedToZipkin();
  }

  @Test void extractTraceContext() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("X-B3-TraceId", "0000000000000001");
    map.put("X-B3-SpanId", "0000000000000002");
    map.put("X-B3-Sampled", "1");
    map.put("User-Id", "sammy");

    BraveSpanContext openTracingContext =
      opentracing.extract(Format.Builtin.HTTP_HEADERS, new TextMapAdapter(map));

    assertThat(openTracingContext.context)
      .isEqualTo(TraceContext.newBuilder()
        .traceId(1L)
        .spanId(2L)
        .sampled(true).build());

    assertThat(openTracingContext.baggageItems())
      .containsExactly(entry(BAGGAGE_FIELD.name(), "sammy"));
  }

  @Test void injectTraceContext() {
    TraceContext context = TraceContext.newBuilder()
      .traceId(1L)
      .spanId(2L)
      .sampled(true).build();

    Map<String, String> map = new LinkedHashMap<>();
    TextMapAdapter request = new TextMapAdapter(map);
    opentracing.inject(new BraveSpanContext(context), Format.Builtin.HTTP_HEADERS, request);

    assertThat(map).containsExactly(
      entry("X-B3-TraceId", "0000000000000001"),
      entry("X-B3-SpanId", "0000000000000002"),
      entry("X-B3-Sampled", "1")
    );
  }

  @Test void injectRemoteSpanTraceContext() {
    BraveSpan openTracingSpan = opentracing.buildSpan("encode")
        .withTag("lc", "codec")
        .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_PRODUCER)
        .withStartTimestamp(1L).start();

    Map<String, String> map = new LinkedHashMap<>();
    TextMapAdapter request = new TextMapAdapter(map);
    opentracing.inject(openTracingSpan.context(), Format.Builtin.HTTP_HEADERS, request);

    assertThat(map).containsOnlyKeys("b3");

    openTracingSpan.unwrap().abandon();
  }

  void checkSpanReportedToZipkin() {
    assertThat(spans).first().satisfies(s -> {
        assertThat(s.name()).isEqualTo("encode");
        assertThat(s.startTimestamp()).isEqualTo(1L);
        assertThat(s.annotations())
          .containsExactly(entry(2L, "pump fake"));
        assertThat(s.tags())
          .containsExactly(entry("lc", "codec"));
        assertThat(s.finishTimestamp()).isEqualTo(3L);
      }
    );
  }
}
