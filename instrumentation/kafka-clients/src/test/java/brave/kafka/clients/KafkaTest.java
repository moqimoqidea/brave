/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.kafka.clients;

import brave.Tracing;
import brave.baggage.BaggagePropagation;
import brave.baggage.BaggagePropagationConfig;
import brave.handler.MutableSpan;
import brave.propagation.B3Propagation;
import brave.propagation.Propagation;
import brave.propagation.StrictCurrentTraceContext;
import brave.propagation.TraceContext;
import brave.test.TestSpanHandler;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.AfterEach;

import static brave.test.ITRemote.BAGGAGE_FIELD;
import static brave.test.ITRemote.BAGGAGE_FIELD_KEY;
import static org.assertj.core.api.Assertions.assertThat;

public class KafkaTest {
  static final String TEST_TOPIC = "myTopic";
  static final String TEST_KEY = "foo";
  static final String TEST_VALUE = "bar";

  StrictCurrentTraceContext currentTraceContext = StrictCurrentTraceContext.create();
  TestSpanHandler spans = new TestSpanHandler();
  Tracing tracing = Tracing.newBuilder()
      .currentTraceContext(currentTraceContext)
      .addSpanHandler(spans)
      .propagationFactory(BaggagePropagation.newFactoryBuilder(B3Propagation.FACTORY)
          .add(BaggagePropagationConfig.SingleBaggageField.newBuilder(BAGGAGE_FIELD)
              .addKeyName(BAGGAGE_FIELD_KEY)
              .build()).build())
      .build();

  KafkaTracing kafkaTracing = KafkaTracing.create(tracing);
  TraceContext parent = tracing.tracer().newTrace().context();
  TraceContext incoming = tracing.tracer().newTrace().context();

  ConsumerRecord<String, String>
      consumerRecord = new ConsumerRecord<>(TEST_TOPIC, 0, 1L, TEST_KEY, TEST_VALUE);
  ProducerRecord<String, String>
      producerRecord = new ProducerRecord<>(TEST_TOPIC, TEST_KEY, TEST_VALUE);
  RuntimeException error = new RuntimeException("Test exception");

  @AfterEach void close() {
    tracing.close();
    currentTraceContext.close();
  }

  RecordMetadata createRecordMetadata() {
    TopicPartition tp = new TopicPartition("foo", 0);
    long timestamp = 2340234L;
    int keySize = 3;
    int valueSize = 5;
    Long checksum = 908923L;
    return new RecordMetadata(tp, -1L, -1L, timestamp, checksum, keySize, valueSize);
  }

  static void assertChildOf(MutableSpan child, TraceContext parent) {
    assertThat(child.parentId())
        .isEqualTo(parent.spanIdString());
  }

  static <K, V> void addB3MultiHeaders(TraceContext parent, ConsumerRecord<K, V> record) {
    Propagation.B3_STRING.injector(KafkaConsumerRequest.SETTER)
        .inject(parent, new KafkaConsumerRequest(record));
  }

  static Set<Entry<String, String>> lastHeaders(Headers headers) {
    Map<String, String> result = new LinkedHashMap<>();
    headers.forEach(h -> result.put(h.key(), new String(h.value(), StandardCharsets.UTF_8)));
    return result.entrySet();
  }

  static Map<String, String> lastHeaders(MockProducer<String, String> mockProducer) {
    Map<String, String> headers = new LinkedHashMap<>();
    List<ProducerRecord<String, String>> history = mockProducer.history();
    ProducerRecord<String, String> lastRecord = history.get(history.size() - 1);
    for (Header header : lastRecord.headers()) {
      headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
    }
    return headers;
  }
}
