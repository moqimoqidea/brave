/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.http;

import brave.sampler.Matcher;
import brave.sampler.RateLimitingSampler;
import brave.sampler.Sampler;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static brave.http.HttpRequestMatchers.methodEquals;
import static brave.http.HttpRequestMatchers.pathStartsWith;
import static brave.sampler.Matchers.and;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class HttpRuleSamplerTest {
  @Mock HttpClientRequest httpClientRequest;
  @Mock HttpServerRequest httpServerRequest;

  @Test void matches() {
    Map<Sampler, Boolean> samplerToAnswer = new LinkedHashMap<>();
    samplerToAnswer.put(Sampler.ALWAYS_SAMPLE, true);
    samplerToAnswer.put(Sampler.NEVER_SAMPLE, false);

    samplerToAnswer.forEach((sampler, answer) -> {
      HttpRuleSampler ruleSampler = HttpRuleSampler.newBuilder()
        .putRule(pathStartsWith("/foo"), sampler)
        .build();

      when(httpClientRequest.path()).thenReturn("/foo");

      assertThat(ruleSampler.trySample(httpClientRequest))
        .isEqualTo(answer);

      when(httpServerRequest.path()).thenReturn("/foo");

      // consistent answer
      assertThat(ruleSampler.trySample(httpServerRequest))
        .isEqualTo(answer);
    });
  }

  @Test void nullOnNull() {
    HttpRuleSampler ruleSampler = HttpRuleSampler.newBuilder()
      .putRule(pathStartsWith("/bar"), Sampler.ALWAYS_SAMPLE)
      .build();

    assertThat(ruleSampler.trySample(null))
      .isNull();
  }

  @Test void unmatched() {
    HttpRuleSampler ruleSampler = HttpRuleSampler.newBuilder()
      .putRule(pathStartsWith("/bar"), Sampler.ALWAYS_SAMPLE)
      .build();

    when(httpClientRequest.path()).thenReturn("/foo");

    assertThat(ruleSampler.trySample(httpClientRequest))
      .isNull();

    when(httpServerRequest.path()).thenReturn("/foo");

    // consistent answer
    assertThat(ruleSampler.trySample(httpServerRequest))
      .isNull();
  }

  @Test void exampleCustomMatcher() {
    Matcher<HttpRequest> playInTheUSA = request -> {
      if (!"/play".equals(request.path())) return false;
      String url = request.url();
      if (url == null) return false;
      String query = URI.create(url).getQuery();
      return query != null && query.contains("country=US");
    };

    HttpRuleSampler sampler = HttpRuleSampler.newBuilder()
      .putRule(playInTheUSA, RateLimitingSampler.create(100))
      .build();

    when(httpServerRequest.path()).thenReturn("/play");
    when(httpServerRequest.url())
      .thenReturn("https://movies/play?user=gumby&country=US&device=iphone");

    assertThat(sampler.trySample(httpServerRequest))
      .isTrue();

    when(httpServerRequest.path()).thenReturn("/play");
    when(httpServerRequest.url())
      .thenReturn("https://movies/play?user=gumby&country=ES&device=iphone");

    assertThat(sampler.trySample(httpServerRequest))
      .isNull(); // unmatched because country isn't ES
  }

  @Test void putAllRules() {
    HttpRuleSampler base = HttpRuleSampler.newBuilder()
      .putRule(and(methodEquals("GET"), pathStartsWith("/foo")), Sampler.NEVER_SAMPLE)
      .build();

    HttpRuleSampler extended = HttpRuleSampler.newBuilder()
      .putAllRules(base)
      .build();

    when(httpServerRequest.method()).thenReturn("POST");

    assertThat(extended.trySample(httpServerRequest))
      .isNull();

    when(httpServerRequest.method()).thenReturn("GET");
    when(httpServerRequest.path()).thenReturn("/foo");

    assertThat(extended.trySample(httpServerRequest))
      .isFalse();
  }

  // empty may sound unintuitive, but it allows use of the same type when always deferring
  @Test void noRulesOk() {
    HttpRuleSampler.newBuilder().build();
  }
}
