/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.kafka.clients;

import brave.Span.Kind;
import brave.handler.MutableSpan;
import brave.internal.codec.HexCodec;
import brave.messaging.MessagingRuleSampler;
import brave.messaging.MessagingTracing;
import brave.propagation.Propagation;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.propagation.TraceIdContext;
import brave.sampler.Sampler;
import brave.test.IntegrationTestSpanHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static brave.kafka.clients.KafkaTags.KAFKA_TOPIC_TAG;
import static brave.kafka.clients.KafkaTest.TEST_KEY;
import static brave.kafka.clients.KafkaTest.TEST_VALUE;
import static brave.messaging.MessagingRequestMatchers.channelNameEquals;
import static brave.messaging.MessagingRequestMatchers.operationEquals;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
public class ITKafkaTracing extends ITKafka { // public for src/it
  @Container KafkaContainer kafka = new KafkaContainer();
  @RegisterExtension IntegrationTestSpanHandler producerSpanHandler =
    new IntegrationTestSpanHandler();
  @RegisterExtension IntegrationTestSpanHandler consumerSpanHandler =
    new IntegrationTestSpanHandler();

  KafkaTracing producerTracing = KafkaTracing.create(
    tracingBuilder(Sampler.ALWAYS_SAMPLE).localServiceName("producer")
      .clearSpanHandlers().addSpanHandler(producerSpanHandler).build()
  );

  KafkaTracing consumerTracing = KafkaTracing.create(
    tracingBuilder(Sampler.ALWAYS_SAMPLE).localServiceName("consumer")
      .clearSpanHandlers().addSpanHandler(consumerSpanHandler).build()
  );

  Producer<String, String> producer;
  Consumer<String, String> consumer;

  @Override @AfterEach protected void close() throws Exception {
    if (producer != null) producer.close();
    if (consumer != null) consumer.close();
    super.close();
  }

  @Test void poll_creates_one_consumer_span_per_extracted_context() {
    String topic1 = testName + "1";
    String topic2 = testName + "2";

    producer = createTracingProducer();
    consumer = createTracingConsumer(topic1, topic2);

    send(new ProducerRecord<>(topic1, TEST_KEY, TEST_VALUE));
    send(new ProducerRecord<>(topic2, TEST_KEY, TEST_VALUE));

    ConsumerRecords<String, String> records = consumer.poll(10000);

    assertThat(records).hasSize(2);
    MutableSpan producerSpan1 = takeProducerSpan(), producerSpan2 = takeProducerSpan();
    MutableSpan consumerSpan1 = takeConsumerSpan(), consumerSpan2 = takeConsumerSpan();

    // Check to see the trace is continued between the producer and the consumer
    // we don't know the order the spans will come in. Correlate with the tag instead.
    String firstTopic = producerSpan1.tags().get(KAFKA_TOPIC_TAG);
    if (firstTopic.equals(consumerSpan1.tags().get(KAFKA_TOPIC_TAG))) {
      assertThat(producerSpan1.traceId())
        .isEqualTo(consumerSpan1.traceId());
      assertThat(producerSpan2.traceId())
        .isEqualTo(consumerSpan2.traceId());
    } else {
      assertThat(producerSpan1.traceId())
        .isEqualTo(consumerSpan2.traceId());
      assertThat(producerSpan2.traceId())
        .isEqualTo(consumerSpan1.traceId());
    }
  }

  void send(ProducerRecord<String, String> record) {
    BlockingCallback callback = new BlockingCallback();
    producer.send(record, callback);
    callback.join();
  }

  MutableSpan takeProducerSpan() {
    return producerSpanHandler.takeRemoteSpan(Kind.PRODUCER);
  }

  MutableSpan takeConsumerSpan() {
    return consumerSpanHandler.takeRemoteSpan(Kind.CONSUMER);
  }

  @Test void poll_creates_one_consumer_span_per_topic() {
    String topic1 = testName + "1";
    String topic2 = testName + "2";

    producer = kafka.createStringProducer(); // not traced
    consumer = createTracingConsumer(topic1, topic2);

    for (int i = 0; i < 5; i++) {
      send(new ProducerRecord<>(topic1, TEST_KEY, TEST_VALUE));
      send(new ProducerRecord<>(topic2, TEST_KEY, TEST_VALUE));
    }

    ConsumerRecords<String, String> records = consumer.poll(10000);

    assertThat(records).hasSize(10);

    takeConsumerSpan();
    takeConsumerSpan();
  }

  @Test void creates_dependency_links() {
    producer = createTracingProducer();
    consumer = createTracingConsumer();

    send(new ProducerRecord<>(testName, TEST_KEY, TEST_VALUE));

    consumer.poll(10000);

    MutableSpan producerSpan = takeProducerSpan();
    MutableSpan consumerSpan = takeConsumerSpan();

    assertThat(producerSpan.localServiceName()).isEqualTo("producer");
    assertThat(producerSpan.remoteServiceName()).isEqualTo("kafka");
    assertThat(consumerSpan.remoteServiceName()).isEqualTo("kafka");
    assertThat(consumerSpan.localServiceName()).isEqualTo("consumer");
  }

  @Test void nextSpan_makes_child() {
    producer = createTracingProducer();
    consumer = createTracingConsumer();

    send(new ProducerRecord<>(testName, TEST_KEY, TEST_VALUE));

    ConsumerRecords<String, String> records = consumer.poll(10000);

    assertThat(records).hasSize(1);
    MutableSpan producerSpan = takeProducerSpan();
    MutableSpan consumerSpan = takeConsumerSpan();

    for (ConsumerRecord<String, String> record : records) {
      brave.Span processor = kafkaTracing.nextSpan(record);

      assertThat(consumerSpan.tags())
        .containsEntry(KAFKA_TOPIC_TAG, record.topic());

      assertThat(processor.context().traceIdString()).isEqualTo(consumerSpan.traceId());
      assertThat(processor.context().parentIdString()).isEqualTo(consumerSpan.id());

      processor.start().name("processor").finish();

      // The processor doesn't taint the consumer span which has already finished
      MutableSpan processorSpan = testSpanHandler.takeLocalSpan();
      assertThat(processorSpan.id())
        .isNotEqualTo(consumerSpan.id());
    }
  }

  static class TraceIdOnlyPropagation extends Propagation.Factory implements Propagation<String> {
    static final String TRACE_ID = "x-b3-traceid";

    @Override public List<String> keys() {
      return Collections.singletonList(TRACE_ID);
    }

    @Override public <R> TraceContext.Injector<R> injector(Setter<R, String> setter) {
      return (traceContext, request) -> setter.put(request, TRACE_ID, traceContext.traceIdString());
    }

    @Override public <R> TraceContext.Extractor<R> extractor(Getter<R, String> getter) {
      return request -> {
        String result = getter.get(request, TRACE_ID);
        if (result == null) return TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY);
        return TraceContextOrSamplingFlags.create(TraceIdContext.newBuilder()
          .traceId(HexCodec.lowerHexToUnsignedLong(result))
          .build());
      };
    }

    @Override public Propagation<String> get() {
      return this;
    }
  }

  @Test void continues_a_trace_when_only_trace_id_propagated() {
    consumerTracing = KafkaTracing.create(tracingBuilder(Sampler.ALWAYS_SAMPLE)
      .clearSpanHandlers().addSpanHandler(consumerSpanHandler)
      .propagationFactory(new TraceIdOnlyPropagation())
      .build());
    producerTracing = KafkaTracing.create(tracingBuilder(Sampler.ALWAYS_SAMPLE)
      .clearSpanHandlers().addSpanHandler(producerSpanHandler)
      .propagationFactory(new TraceIdOnlyPropagation())
      .build());

    producer = createTracingProducer();
    consumer = createTracingConsumer();

    send(new ProducerRecord<>(testName, TEST_KEY, TEST_VALUE));

    // intentionally using deprecated method as we are checking the same class in an invoker test
    // under src/it. If we want to explicitly tests the Duration arg, we will have to subclass.
    ConsumerRecords<String, String> records = consumer.poll(10_000L);

    assertThat(records).hasSize(1);
    MutableSpan producerSpan = takeProducerSpan();
    MutableSpan consumerSpan = takeConsumerSpan();

    assertThat(producerSpan.traceId())
      .isEqualTo(consumerSpan.traceId());

    for (ConsumerRecord<String, String> record : records) {
      TraceContext forProcessor = consumerTracing.nextSpan(record).context();

      assertThat(forProcessor.traceIdString()).isEqualTo(consumerSpan.traceId());
    }
  }

  @Test void customSampler_producer() {
    String topic = testName;

    producerTracing = KafkaTracing.create(
      MessagingTracing.newBuilder(producerTracing.messagingTracing.tracing())
        .producerSampler(MessagingRuleSampler.newBuilder()
          .putRule(channelNameEquals(topic), Sampler.NEVER_SAMPLE)
          .build())
        .build());

    producer = createTracingProducer();
    consumer = createTracingConsumer();

    send(new ProducerRecord<>(topic, TEST_KEY, TEST_VALUE));

    // intentionally using deprecated method as we are checking the same class in an invoker test
    // under src/it. If we want to explicitly tests the Duration arg, we will have to subclass.
    ConsumerRecords<String, String> records = consumer.poll(10_000L);

    assertThat(records).hasSize(1);
    checkB3Unsampled(records);

    // since the producer was unsampled, the consumer should be unsampled also due to propagation

    // @After will also check that both the producer and consumer were not sampled
  }

  void checkB3Unsampled(ConsumerRecords<String, String> records) {
    // Check that the injected context was not sampled
    assertThat(records)
      .extracting(ConsumerRecord::headers)
      .flatExtracting(TracingConsumerTest::lastHeaders)
      .hasSize(1)
      .allSatisfy(e -> {
        assertThat(e.getKey()).isEqualTo("b3");
        assertThat(e.getValue()).endsWith("-0");
      });
  }

  @Test void customSampler_consumer() {
    String topic = testName;

    consumerTracing = KafkaTracing.create(
      MessagingTracing.newBuilder(consumerTracing.messagingTracing.tracing())
        .consumerSampler(MessagingRuleSampler.newBuilder()
          .putRule(operationEquals("receive"), Sampler.NEVER_SAMPLE)
          .build()).build());

    producer = kafka.createStringProducer(); // intentionally don't trace the producer
    consumer = createTracingConsumer();

    send(new ProducerRecord<>(topic, TEST_KEY, TEST_VALUE));

    // intentionally using deprecated method as we are checking the same class in an invoker test
    // under src/it. If we want to explicitly tests the Duration arg, we will have to subclass.
    ConsumerRecords<String, String> records = consumer.poll(10_000L);

    assertThat(records).hasSize(1);
    checkB3Unsampled(records);

    // @After will also check that the consumer was not sampled
  }

  Consumer<String, String> createTracingConsumer(String... topics) {
    if (topics.length == 0) topics = new String[] {testName};
    KafkaConsumer<String, String> consumer = kafka.createStringConsumer();
    List<TopicPartition> assignments = new ArrayList<>();
    for (String topic : topics) {
      assignments.add(new TopicPartition(topic, 0));
    }
    consumer.assign(assignments);
    return consumerTracing.consumer(consumer);
  }

  Producer<String, String> createTracingProducer() {
    KafkaProducer<String, String> producer = kafka.createStringProducer();
    return producerTracing.producer(producer);
  }
}
