/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.sampler;

import brave.Request;
import brave.internal.Nullable;

/**
 * Decides whether to start a new trace based on request properties such as an HTTP path.
 *
 * <p>Ex. Here's a sampler that only traces api requests
 * <pre>{@code
 * serverSampler = new SamplerFunction<HttpRequest>() {
 *   @Override public Boolean trySample(HttpRequest request) {
 *     return request.path().startsWith("/api");
 *   }
 * });
 * }</pre>
 *
 * @param <T> type of the input, for example a request or method
 * @see SamplerFunctions
 * @see Request
 * @since 5.8
 */
// interface, not abstract type, to allow backporting of existing samplers.
// This implies we cannot add new methods later, as the bytecode level of Brave core is 1.6
public interface SamplerFunction<T> {
  /**
   * Returns an overriding sampling decision for a new trace. Returning null is typically used to
   * defer to the {@link brave.Tracing#sampler() trace ID sampler}.
   *
   * @param arg parameter to evaluate for a sampling decision. null input results in a null result
   * @return true to sample a new trace or false to deny. Null defers the decision.
   * @since 5.8
   */
  @Nullable Boolean trySample(@Nullable T arg);
}
