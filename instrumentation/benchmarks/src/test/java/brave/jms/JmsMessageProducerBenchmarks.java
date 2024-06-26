/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.jms;

import brave.Tracing;
import java.util.concurrent.TimeUnit;
import javax.jms.CompletionListener;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageNotWriteableException;
import javax.jms.MessageProducer;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@Measurement(iterations = 5, time = 1)
@Warmup(iterations = 10, time = 1)
@Fork(3)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class JmsMessageProducerBenchmarks {
  ActiveMQTextMessage message = new ActiveMQTextMessage();
  MessageProducer producer, tracingProducer;

  @Setup(Level.Trial) public void init() throws MessageNotWriteableException {
    Tracing tracing = Tracing.newBuilder().build();
    producer = new FakeMessageProducer();
    message.setText("value");
    tracingProducer = TracingMessageProducer.create(producer, JmsTracing.create(tracing));
  }

  @TearDown(Level.Trial) public void close() {
    Tracing.current().close();
  }

  /** Should be near zero. This mainly ensures exceptions aren't raised */
  @Benchmark public void send_baseCase() throws Exception {
    producer.send(message);
  }

  @Benchmark public void send_traced() throws Exception {
    tracingProducer.send(message);
  }

  // Convenience main entry-point
  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
      .addProfiler("gc")
      .include(".*" + JmsMessageProducerBenchmarks.class.getSimpleName())
      .build();

    new Runner(opt).run();
  }

  static final class FakeMessageProducer implements MessageProducer {

    @Override public void setDisableMessageID(boolean value) {
    }

    @Override public boolean getDisableMessageID() {
      return false;
    }

    @Override public void setDisableMessageTimestamp(boolean value) {
    }

    @Override public boolean getDisableMessageTimestamp() {
      return false;
    }

    @Override public void setDeliveryMode(int deliveryMode) {
    }

    @Override public int getDeliveryMode() {
      return 0;
    }

    @Override public void setPriority(int defaultPriority) {
    }

    @Override public int getPriority() {
      return 0;
    }

    @Override public void setTimeToLive(long timeToLive) {
    }

    @Override public long getTimeToLive() {
      return 0;
    }

    @Override public void setDeliveryDelay(long l) {
    }

    @Override public long getDeliveryDelay() {
      return 0;
    }

    @Override public Destination getDestination() {
      return null;
    }

    @Override public void close() {
    }

    @Override public void send(Message message) {
    }

    @Override public void send(Message message, int deliveryMode, int priority, long timeToLive) {
    }

    @Override public void send(Destination destination, Message message) {
    }

    @Override public void send(Destination destination, Message message, int deliveryMode,
      int priority, long timeToLive) {
    }

    @Override public void send(Message message, CompletionListener completionListener) {
    }

    @Override public void send(Message message, int i, int i1, long l,
      CompletionListener completionListener) {
    }

    @Override public void send(Destination destination, Message message,
      CompletionListener completionListener) {
    }

    @Override public void send(Destination destination, Message message, int i, int i1, long l,
      CompletionListener completionListener) {
    }
  }
}
