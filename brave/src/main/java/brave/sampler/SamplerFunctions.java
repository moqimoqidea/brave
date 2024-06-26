/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.sampler;

import brave.internal.Nullable;

/**
 * Convenience sampling functions.
 *
 * @see SamplerFunction
 * @since 5.8
 */
public final class SamplerFunctions {
  /**
   * Returns a function that returns null on null input instead of invoking the delegate with null.
   *
   * @since 5.8
   */
  public static <T> SamplerFunction<T> nullSafe(SamplerFunction<T> delegate) {
    if (delegate == null) throw new NullPointerException("delegate == null");
    if (delegate instanceof Constants || delegate instanceof NullSafe) return delegate;
    return new NullSafe<T>(delegate);
  }

  static final class NullSafe<T> implements SamplerFunction<T> {
    final SamplerFunction<T> delegate;

    NullSafe(SamplerFunction<T> delegate) {
      this.delegate = delegate;
    }

    @Override public Boolean trySample(T arg) {
      if (arg == null) return null;
      return delegate.trySample(arg);
    }

    @Override public String toString() {
      return "NullSafe(" + delegate + ")";
    }
  }

  /**
   * Ignores the argument and returns null. This is typically used to defer to the {@link
   * brave.Tracing#sampler() trace ID sampler}.
   *
   * @since 5.8
   */
  // using a method instead of exposing a constant allows this to be used for any argument type
  public static <T> SamplerFunction<T> deferDecision() {
    return (SamplerFunction<T>) Constants.DEFER_DECISION;
  }

  /**
   * Ignores the argument and returns false. This means it will never start new traces.
   *
   * <p>For example, you may wish to only capture traces if they originated from an inbound server
   * request. Such a policy would filter out client requests made during bootstrap.
   *
   * @since 5.8
   */
  // using a method instead of exposing a constant allows this to be used for any argument type
  public static <T> SamplerFunction<T> neverSample() {
    return (SamplerFunction<T>) Constants.NEVER_SAMPLE;
  }

  enum Constants implements SamplerFunction<Object> {
    DEFER_DECISION {
      @Override @Nullable public Boolean trySample(Object request) {
        return null;
      }

      @Override public String toString() {
        return "DeferDecision";
      }
    },
    NEVER_SAMPLE {
      @Override @Nullable public Boolean trySample(Object request) {
        return false;
      }

      @Override public String toString() {
        return "NeverSample";
      }
    }
  }
}
