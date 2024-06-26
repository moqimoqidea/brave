/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.features.handler;

import brave.Tags;
import brave.Tracing;
import brave.handler.MutableSpan;
import brave.handler.MutableSpanBytesEncoder;
import brave.handler.SpanHandler;
import brave.propagation.B3SingleFormat;
import brave.propagation.TraceContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is an example of why {@link MutableSpanBytesEncoder} was written. Particularly, it allows
 * direct encoding from {@link MutableSpan} into JSON without converting to Zipkin model first.
 */
class MutableSpanAsyncReporterTest {
  MutableSpanBytesEncoder mutableSpanBytesEncoder =
    MutableSpanBytesEncoder.zipkinJsonV2(Tags.ERROR);

  List<byte[]> messages = new ArrayList<>();

  SpanHandler spanHandlerAdapter = new SpanHandler() {
    @Override public boolean end(TraceContext context, MutableSpan span, SpanHandler.Cause cause) {
      if (!Boolean.TRUE.equals(context.sampled())) return true;
      messages.add(mutableSpanBytesEncoder.encode(span));
      return true;
    }
  };

  Tracing tracing = Tracing.newBuilder()
    .localServiceName("Aa")
    .localIp("1.2.3.4")
    .localPort(80)
    .addSpanHandler(spanHandlerAdapter)
    .build();

  @AfterEach void close() {
    tracing.close();
  }

  /** This mainly shows endpoints are taken from Brave, and error is back-filled. */
  @Test void basicSpan() {
    TraceContext context = B3SingleFormat.parseB3SingleFormat(
      "50d980fffa300f29-86154a4ba6e91385-1"
    ).context();

    tracing.tracer().toSpan(context).name("test")
      .start(1L)
      .error(new RuntimeException("this cake is a lie"))
      .finish(3L);

    assertThat(messages).hasSize(1).first()
      .extracting(b -> new String(b, UTF_8))
      .isEqualTo(
        "{\"traceId\":\"50d980fffa300f29\","
          + "\"id\":\"86154a4ba6e91385\","
          + "\"name\":\"test\","
          + "\"timestamp\":1,"
          + "\"duration\":2,"
          + "\"localEndpoint\":{"
          + "\"serviceName\":\"Aa\","
          + "\"ipv4\":\"1.2.3.4\","
          + "\"port\":80},"
          + "\"tags\":{\"error\":\"this cake is a lie\"}}"
      );
  }
}
