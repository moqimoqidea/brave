/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.http;

import brave.Span;
import brave.propagation.Propagation.RemoteGetter;

/**
 * Marks an interface for use in {@link HttpServerHandler#handleReceive(HttpServerRequest)}. This
 * gives a standard type to consider when parsing an incoming context.
 *
 * @see HttpServerResponse
 * @since 5.7
 */
public abstract class HttpServerRequest extends HttpRequest {
  static final RemoteGetter<HttpServerRequest> GETTER = new RemoteGetter<HttpServerRequest>() {
    @Override public Span.Kind spanKind() {
      return Span.Kind.SERVER;
    }

    @Override public String get(HttpServerRequest request, String key) {
      return request.header(key);
    }

    @Override public String toString() {
      return "HttpServerRequest::header";
    }
  };

  @Override public final Span.Kind spanKind() {
    return Span.Kind.SERVER;
  }

  /**
   * Used by {@link HttpServerHandler#handleReceive(HttpServerRequest)} to add remote socket
   * information about the client from the {@linkplain #unwrap() delegate}.
   *
   * <p>By default, this tries to parse the {@linkplain #parseClientIpFromXForwardedFor(Span)
   * forwarded IP}. Override to add client socket information when forwarded info is not available.
   *
   * <p>Aside: It is more likely a server request object will be able to parse socket information
   * as opposed to a client object. This is because client requests are often parsed before a
   * network route is chosen, whereas server requests are parsed after the network layer.
   *
   * @return true if parsing was successful.
   * @since 5.7
   */
  public boolean parseClientIpAndPort(Span span) {
    return parseClientIpFromXForwardedFor(span);
  }

  /**
   * Uses the first value in the "X-Forwarded-For" header, or returns false if not present.
   *
   * @since 5.10
   */
  protected boolean parseClientIpFromXForwardedFor(Span span) {
    String forwardedFor = header("X-Forwarded-For");
    if (forwardedFor == null) return false;
    int indexOfComma = forwardedFor.indexOf(',');
    if (indexOfComma != -1) forwardedFor = forwardedFor.substring(0, indexOfComma);
    return span.remoteIpAndPort(forwardedFor, 0);
  }
}
