/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.dubbo;

import brave.Span;
import java.util.Collections;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static brave.Span.Kind.CLIENT;
import static brave.Span.Kind.SERVER;
import static org.mockito.Mockito.mock;

public class FinishSpanTest extends ITTracingFilter {
  DubboClientRequest clientRequest =
      new DubboClientRequest(mock(Invoker.class), mock(Invocation.class), Collections.emptyMap());
  DubboServerRequest serverRequest =
      new DubboServerRequest(mock(Invoker.class), mock(Invocation.class));
  TracingFilter filter;

  @BeforeEach void setup() {
    filter = init();
  }

  @Test void finish_null_result_and_error_DubboClientRequest() {
    Span span = tracing.tracer().nextSpan().kind(CLIENT).start();

    FinishSpan.finish(filter, clientRequest, null, null, span);

    testSpanHandler.takeRemoteSpan(CLIENT);
  }

  @Test void finish_null_result_and_error_DubboServerRequest() {
    Span span = tracing.tracer().nextSpan().kind(SERVER).start();

    FinishSpan.finish(filter, serverRequest, null, null, span);

    testSpanHandler.takeRemoteSpan(SERVER);
  }

  @Test void finish_result_but_null_error_DubboClientRequest() {
    Span span = tracing.tracer().nextSpan().kind(CLIENT).start();

    FinishSpan.finish(filter, clientRequest, mock(Result.class), null, span);

    testSpanHandler.takeRemoteSpan(CLIENT);
  }

  @Test void finish_result_but_null_error_DubboServerRequest() {
    Span span = tracing.tracer().nextSpan().kind(SERVER).start();

    FinishSpan.finish(filter, serverRequest, mock(Result.class), null, span);

    testSpanHandler.takeRemoteSpan(SERVER);
  }

  @Test void finish_error_but_null_result_DubboClientRequest() {
    Span span = tracing.tracer().nextSpan().kind(CLIENT).start();

    Throwable error = new RuntimeException("melted");
    FinishSpan.finish(filter, clientRequest, null, error, span);

    testSpanHandler.takeRemoteSpanWithError(CLIENT, error);
  }

  @Test void finish_error_but_null_result_DubboServerRequest() {
    Span span = tracing.tracer().nextSpan().kind(SERVER).start();

    Throwable error = new RuntimeException("melted");
    FinishSpan.finish(filter, serverRequest, null, error, span);

    testSpanHandler.takeRemoteSpanWithError(SERVER, error);
  }

  @Test void create_null_result_value_and_error_DubboClientRequest() {
    Span span = tracing.tracer().nextSpan().kind(CLIENT).start();

    FinishSpan.create(filter, clientRequest, mock(Result.class), span)
        .accept(null, null);

    testSpanHandler.takeRemoteSpan(CLIENT);
  }

  @Test void create_null_result_value_and_error_DubboServerRequest() {
    Span span = tracing.tracer().nextSpan().kind(SERVER).start();

    FinishSpan.create(filter, serverRequest, mock(Result.class), span)
        .accept(null, null);

    testSpanHandler.takeRemoteSpan(SERVER);
  }

  @Test void create_result_value_but_null_error_DubboClientRequest() {
    Span span = tracing.tracer().nextSpan().kind(CLIENT).start();

    FinishSpan.create(filter, clientRequest, mock(Result.class), span)
        .accept(new Object(), null);

    testSpanHandler.takeRemoteSpan(CLIENT);
  }

  @Test void create_result_value_but_null_error_DubboServerRequest() {
    Span span = tracing.tracer().nextSpan().kind(SERVER).start();

    FinishSpan.create(filter, serverRequest, mock(Result.class), span)
        .accept(new Object(), null);

    testSpanHandler.takeRemoteSpan(SERVER);
  }

  @Test void create_error_but_null_result_value_DubboClientRequest() {
    Span span = tracing.tracer().nextSpan().kind(CLIENT).start();

    Throwable error = new RuntimeException("melted");
    FinishSpan.create(filter, clientRequest, mock(Result.class), span)
        .accept(null, error);

    testSpanHandler.takeRemoteSpanWithError(CLIENT, error);
  }

  @Test void create_error_but_null_result_value_DubboServerRequest() {
    Span span = tracing.tracer().nextSpan().kind(SERVER).start();

    Throwable error = new RuntimeException("melted");
    FinishSpan.create(filter, serverRequest, mock(Result.class), span)
        .accept(null, error);

    testSpanHandler.takeRemoteSpanWithError(SERVER, error);
  }
}
