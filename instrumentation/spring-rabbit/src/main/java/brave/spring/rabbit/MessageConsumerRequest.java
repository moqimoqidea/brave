/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.spring.rabbit;

import brave.Span.Kind;
import brave.messaging.ConsumerRequest;
import brave.propagation.Propagation.RemoteGetter;
import brave.propagation.Propagation.RemoteSetter;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

// intentionally not yet public until we add tag parsing functionality
final class MessageConsumerRequest extends ConsumerRequest {
  static final RemoteGetter<MessageConsumerRequest> GETTER =
      new RemoteGetter<MessageConsumerRequest>() {
        @Override public Kind spanKind() {
          return Kind.CONSUMER;
        }

        @Override public String get(MessageConsumerRequest request, String name) {
          return MessageHeaders.getHeaderIfString(request.delegate, name);
        }

        @Override public String toString() {
          return "MessageProperties::getHeader";
        }
      };

  static final RemoteSetter<MessageConsumerRequest> SETTER =
      new RemoteSetter<MessageConsumerRequest>() {
        @Override public Kind spanKind() {
          return Kind.CONSUMER;
        }

        @Override public void put(MessageConsumerRequest request, String name, String value) {
          MessageHeaders.setHeader(request.delegate, name, value);
        }

        @Override public String toString() {
          return "MessageProperties::setHeader";
        }
      };

  final Message delegate;

  MessageConsumerRequest(Message delegate) {
    if (delegate == null) throw new NullPointerException("delegate == null");
    this.delegate = delegate;
  }

  @Override public Kind spanKind() {
    return Kind.CONSUMER;
  }

  @Override public Object unwrap() {
    return delegate;
  }

  @Override public String operation() {
    return "receive";
  }

  @Override public String channelKind() {
    return "queue";
  }

  @Override public String channelName() {
    MessageProperties properties = delegate.getMessageProperties();
    return properties != null ? properties.getConsumerQueue() : null;
  }

  @Override public String messageId() {
    MessageProperties properties = delegate.getMessageProperties();
    return properties != null ? properties.getMessageId() : null;
  }
}
