/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.spring.rabbit;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.internal.Nullable;
import brave.messaging.MessagingRequest;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.sampler.SamplerFunction;
import com.rabbitmq.client.Channel;
import java.util.List;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import static brave.Span.Kind.CONSUMER;
import static brave.internal.Throwables.propagateIfFatal;
import static brave.spring.rabbit.SpringRabbitTracing.RABBIT_EXCHANGE;
import static brave.spring.rabbit.SpringRabbitTracing.RABBIT_QUEUE;
import static brave.spring.rabbit.SpringRabbitTracing.RABBIT_ROUTING_KEY;

/**
 * TracingRabbitListenerAdvice is an AOP advice to be used with the {@link
 * SimpleMessageListenerContainer#setAdviceChain(Advice...) SimpleMessageListenerContainer} in order
 * to add tracing functionality to a spring-rabbit managed message consumer. While a majority of
 * brave's instrumentation points is implemented using a delegating wrapper approach, this extension
 * point was ideal as it covers both programmatic and {@link RabbitListener} annotation driven amqp
 * consumers.
 *
 * The spans are modeled as a duration 1 {@link Span.Kind#CONSUMER} span to represent consuming the
 * message from the rabbit broker with a child span representing the processing of the message.
 */
final class TracingRabbitListenerAdvice implements MethodInterceptor {

  final SpringRabbitTracing springRabbitTracing;
  final Tracing tracing;
  final Tracer tracer;
  final Extractor<MessageConsumerRequest> extractor;
  final SamplerFunction<MessagingRequest> sampler;
  @Nullable final String remoteServiceName;

  TracingRabbitListenerAdvice(SpringRabbitTracing springRabbitTracing) {
    this.springRabbitTracing = springRabbitTracing;
    this.tracing = springRabbitTracing.tracing;
    this.tracer = tracing.tracer();
    this.extractor = springRabbitTracing.consumerExtractor;
    this.sampler = springRabbitTracing.consumerSampler;
    this.remoteServiceName = springRabbitTracing.remoteServiceName;
  }

  /**
   * MethodInterceptor for {@link SimpleMessageListenerContainer.ContainerDelegate#invokeListener(Channel,
   * Message)}
   */
  @Override public Object invoke(MethodInvocation methodInvocation) throws Throwable {
    Message message = null;
    if (methodInvocation.getArguments()[1] instanceof List) {
      message = ((List<? extends Message>) methodInvocation.getArguments()[1]).get(0);
    } else {
      message = (Message) methodInvocation.getArguments()[1];
    }
    MessageConsumerRequest request = new MessageConsumerRequest(message);

    TraceContextOrSamplingFlags extracted =
      springRabbitTracing.extractAndClearTraceIdHeaders(extractor, request, message);

    // named for BlockingQueueConsumer.nextMessage, which we can't currently see
    Span consumerSpan = springRabbitTracing.nextMessagingSpan(sampler, request, extracted);
    Span listenerSpan = tracer.newChild(consumerSpan.context());

    if (!consumerSpan.isNoop()) {
      setConsumerSpan(consumerSpan, message.getMessageProperties());

      // incur timestamp overhead only once
      long timestamp = tracing.clock(consumerSpan.context()).currentTimeMicroseconds();
      consumerSpan.start(timestamp);
      long consumerFinish = timestamp + 1L; // save a clock reading
      consumerSpan.finish(consumerFinish);

      // not using scoped span as we want to start with a pre-configured time
      listenerSpan.name("on-message").start(consumerFinish);
    }

    Tracer.SpanInScope scope = tracer.withSpanInScope(listenerSpan);
    Throwable error = null;
    try {
      return methodInvocation.proceed();
    } catch (Throwable t) {
      propagateIfFatal(t);
      error = t;
      throw t;
    } finally {
      if (error != null) listenerSpan.error(error);
      listenerSpan.finish();
      scope.close();
    }
  }

  void setConsumerSpan(Span span, MessageProperties properties) {
    span.name("next-message").kind(CONSUMER);
    maybeTag(span, RABBIT_EXCHANGE, properties.getReceivedExchange());
    maybeTag(span, RABBIT_ROUTING_KEY, properties.getReceivedRoutingKey());
    maybeTag(span, RABBIT_QUEUE, properties.getConsumerQueue());
    if (remoteServiceName != null) span.remoteServiceName(remoteServiceName);
  }

  static void maybeTag(Span span, String tag, String value) {
    if (value != null) span.tag(tag, value);
  }
}
