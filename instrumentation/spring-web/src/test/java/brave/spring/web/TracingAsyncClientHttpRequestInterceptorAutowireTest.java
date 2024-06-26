/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.spring.web;

import brave.Tracing;
import brave.http.HttpTracing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.AsyncClientHttpRequestInterceptor;

public class TracingAsyncClientHttpRequestInterceptorAutowireTest {

  @Configuration static class HttpTracingConfiguration {
    @Bean HttpTracing httpTracing() {
      return HttpTracing.create(Tracing.newBuilder().build());
    }
  }

  @AfterEach void close() {
    Tracing.current().close();
  }

  @Test void autowiredWithBeanConfig() {
    AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
    ctx.register(HttpTracingConfiguration.class);
    ctx.register(TracingAsyncClientHttpRequestInterceptor.class);
    ctx.refresh();

    ctx.getBean(AsyncClientHttpRequestInterceptor.class);
  }
}
