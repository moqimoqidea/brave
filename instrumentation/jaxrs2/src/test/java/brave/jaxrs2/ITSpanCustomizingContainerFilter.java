/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.jaxrs2;

import brave.Span;
import brave.servlet.TracingFilter;
import brave.test.http.ITServletContainer;
import brave.test.http.Jetty9ServerController;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.ws.rs.core.Application;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.plugins.server.servlet.ListenerBootstrap;
import org.jboss.resteasy.plugins.server.servlet.ResteasyBootstrap;
import org.jboss.resteasy.spi.ResteasyConfiguration;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ITSpanCustomizingContainerFilter extends ITServletContainer {
  public ITSpanCustomizingContainerFilter() {
    super(new Jetty9ServerController());
  }

  @Override @Disabled("ContainerRequestContext doesn't include remote address")
  public void reportsClientAddress() {
  }

  @Override @Disabled("resteasy swallows the exception")
  public void setsErrorAndHttpStatusOnUncaughtException() {
  }

  @Override @Disabled("resteasy swallows the exception")
  public void spanHandlerSeesError() {
  }

  @Override @Disabled("resteasy swallows the exception")
  public void setsErrorAndHttpStatusOnUncaughtException_async() {
  }

  @Override @Disabled("resteasy swallows the exception")
  public void spanHandlerSeesError_async() {
  }

  @Test void tagsResource() throws Exception {
    get("/foo");

    assertThat(testSpanHandler.takeRemoteSpan(Span.Kind.SERVER).tags())
      .containsEntry("jaxrs.resource.class", "TestResource")
      .containsEntry("jaxrs.resource.method", "foo");
  }

  @Override public void init(ServletContextHandler handler) {
    // Adds application programmatically as opposed to using web.xml
    handler.addServlet(new ServletHolder(new HttpServletDispatcher()), "/*");
    handler.addEventListener(new TaggingBootstrap(new TestResource(httpTracing)));

    addFilter(handler, TracingFilter.create(httpTracing));
  }

  void addFilter(ServletContextHandler handler, Filter filter) {
    handler.addFilter(new FilterHolder(filter), "/*", EnumSet.allOf(DispatcherType.class));
  }

  static class TaggingBootstrap extends ResteasyBootstrap {

    TaggingBootstrap(Object resource) {
      deployment = new ResteasyDeployment();
      deployment.setApplication(new Application() {
        @Override public Set<Object> getSingletons() {
          return new LinkedHashSet<>(Arrays.asList(
            resource,
            SpanCustomizingContainerFilter.create()
          ));
        }
      });
    }

    @Override public void contextInitialized(ServletContextEvent event) {
      ServletContext servletContext = event.getServletContext();
      ListenerBootstrap config = new ListenerBootstrap(servletContext);
      servletContext.setAttribute(ResteasyDeployment.class.getName(), deployment);
      deployment.getDefaultContextObjects().put(ResteasyConfiguration.class, config);
      config.createDeployment();
      deployment.start();
    }
  }
}
