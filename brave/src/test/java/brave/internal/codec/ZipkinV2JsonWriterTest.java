/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.internal.codec;

import brave.Span;
import brave.Tag;
import brave.Tags;
import brave.handler.MutableSpan;
import brave.handler.MutableSpanTest;
import brave.propagation.TraceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ZipkinV2JsonWriterTest {
  ZipkinV2JsonWriter jsonWriter = new ZipkinV2JsonWriter(Tags.ERROR);
  WriteBuffer buffer = new WriteBuffer(new byte[512], 0);

  MutableSpan clientSpan = new MutableSpan();

  @BeforeEach void createClientSpan() {
    clientSpan.traceId("1"); // note: we didn't pad here.. it is done implicitly
    clientSpan.localRootId("2"); // not a zipkin v2 field
    clientSpan.parentId("2");
    clientSpan.id("3");
    clientSpan.name("get");
    clientSpan.kind(Span.Kind.CLIENT);
    clientSpan.localServiceName("frontend");
    clientSpan.localIp("127.0.0.1");
    clientSpan.remoteServiceName("backend");
    clientSpan.remoteIpAndPort("192.168.99.101", 9000);
    clientSpan.startTimestamp(1000L);
    clientSpan.finishTimestamp(1200L);
    clientSpan.annotate(1100L, "foo");
    clientSpan.tag("http.path", "/api");
    clientSpan.tag("clnt/finagle.version", "6.45.0");
  }

  @Test void sizeInBytes_matchesWhatsWritten() {
    assertThat(jsonWriter.sizeInBytes(MutableSpanTest.PERMUTATIONS.get(0).get()))
      .isEqualTo(2); // {}

    // check for simple bugs
    for (int i = 1, length = MutableSpanTest.PERMUTATIONS.size(); i < length; i++) {
      buffer.pos = 0;
      MutableSpan span = MutableSpanTest.PERMUTATIONS.get(i).get();

      jsonWriter.write(span, buffer);
      int size = jsonWriter.sizeInBytes(span);
      assertThat(jsonWriter.sizeInBytes(span))
        .withFailMessage("expected to write %s bytes: was %s for %s", size, buffer.pos, span)
        .isEqualTo(buffer.pos);
    }
  }

  @Test void specialCharacters() {
    MutableSpan span = new MutableSpan();
    span.name("\u2028 and \u2029");
    span.localServiceName("\"foo");
    span.tag("hello \n", "\t\b");
    span.annotate(1L, "\uD83D\uDCA9");

    jsonWriter.write(span, buffer);
    String string = buffer.toString();
    assertThat(string)
      .isEqualTo(
        "{\"name\":\"\\u2028 and \\u2029\",\"localEndpoint\":{\"serviceName\":\"\\\"foo\"},\"annotations\":[{\"timestamp\":1,\"value\":\"\uD83D\uDCA9\"}],\"tags\":{\"hello \\n\":\"\\t\\b\"}}");

    assertThat(jsonWriter.sizeInBytes(span))
      .isEqualTo(string.getBytes(UTF_8).length);
  }

  @Test void errorTag() {
    MutableSpan span = new MutableSpan();
    span.tag("a", "1");
    span.tag("error", "true");
    span.tag("b", "2");

    jsonWriter.write(span, buffer);
    String string = buffer.toString();
    assertThat(string)
      .isEqualTo("{\"tags\":{\"a\":\"1\",\"error\":\"true\",\"b\":\"2\"}}");

    assertThat(jsonWriter.sizeInBytes(span))
      .isEqualTo(string.getBytes(UTF_8).length);
  }

  @Test void error() {
    MutableSpan span = new MutableSpan();
    span.tag("a", "1");
    span.tag("b", "2");
    span.error(new RuntimeException("ice cream"));

    jsonWriter.write(span, buffer);
    String string = buffer.toString();
    assertThat(string)
      .isEqualTo("{\"tags\":{\"a\":\"1\",\"b\":\"2\",\"error\":\"ice cream\"}}");

    assertThat(jsonWriter.sizeInBytes(span))
      .isEqualTo(string.getBytes(UTF_8).length);
  }

  @Test void existingErrorTagWins() {
    MutableSpan span = new MutableSpan();
    span.tag("a", "1");
    span.tag("error", "true");
    span.tag("b", "2");
    span.error(new RuntimeException("ice cream"));

    jsonWriter.write(span, buffer);
    String string = buffer.toString();
    assertThat(string)
      .isEqualTo("{\"tags\":{\"a\":\"1\",\"error\":\"true\",\"b\":\"2\"}}");

    assertThat(jsonWriter.sizeInBytes(span))
      .isEqualTo(string.getBytes(UTF_8).length);
  }

  @Test void differentErrorTagName() {
    ZipkinV2JsonWriter jsonWriter = new ZipkinV2JsonWriter(new Tag<Throwable>("exception") {
      @Override protected String parseValue(Throwable input, TraceContext context) {
        return input.getMessage();
      }
    });

    MutableSpan span = new MutableSpan();
    span.tag("a", "1");
    span.tag("error", "true");
    span.tag("b", "2");
    span.error(new RuntimeException("ice cream"));

    jsonWriter.write(span, buffer);
    String string = buffer.toString();
    assertThat(string)
      .isEqualTo(
        "{\"tags\":{\"a\":\"1\",\"error\":\"true\",\"b\":\"2\",\"exception\":\"ice cream\"}}");

    assertThat(jsonWriter.sizeInBytes(span))
      .isEqualTo(string.getBytes(UTF_8).length);
  }

  @Test void missingFields_testCases() {
    jsonWriter.write(MutableSpanTest.PERMUTATIONS.get(0).get(), buffer);
    assertThat(buffer.toString()).isEqualTo("{}");

    // check for simple bugs
    for (int i = 1, length = MutableSpanTest.PERMUTATIONS.size(); i < length; i++) {
      buffer.pos = 0;

      MutableSpan span = MutableSpanTest.PERMUTATIONS.get(i).get();
      jsonWriter.write(span, buffer);

      assertThat(buffer.toString())
        .doesNotContain("null")
        .doesNotContain(":0");
    }
  }

  @Test void writeClientSpan() {
    jsonWriter.write(clientSpan, buffer);

    assertThat(buffer.toString()).isEqualTo("{"
      + "\"traceId\":\"0000000000000001\",\"parentId\":\"0000000000000002\",\"id\":\"0000000000000003\","
      + "\"kind\":\"CLIENT\",\"name\":\"get\",\"timestamp\":1000,\"duration\":200,"
      + "\"localEndpoint\":{\"serviceName\":\"frontend\",\"ipv4\":\"127.0.0.1\"},"
      + "\"remoteEndpoint\":{\"serviceName\":\"backend\",\"ipv4\":\"192.168.99.101\",\"port\":9000},"
      + "\"annotations\":[{\"timestamp\":1100,\"value\":\"foo\"}],"
      + "\"tags\":{\"http.path\":\"/api\",\"clnt/finagle.version\":\"6.45.0\"}"
      + "}");
  }
}
