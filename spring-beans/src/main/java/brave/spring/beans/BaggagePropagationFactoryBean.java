/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.spring.beans;

import brave.baggage.BaggagePropagation;
import brave.baggage.BaggagePropagationConfig;
import brave.baggage.BaggagePropagationCustomizer;
import brave.propagation.B3Propagation;
import brave.propagation.Propagation;
import java.util.List;
import org.springframework.beans.factory.FactoryBean;

/** Spring XML config does not support chained builders. This converts accordingly */
public class BaggagePropagationFactoryBean implements FactoryBean {
  Propagation.Factory propagationFactory = B3Propagation.FACTORY;
  List<BaggagePropagationConfig> configs;
  List<BaggagePropagationCustomizer> customizers;

  @Override public Propagation.Factory getObject() {
    BaggagePropagation.FactoryBuilder builder =
      BaggagePropagation.newFactoryBuilder(propagationFactory);
    if (configs != null) {
      builder.clear();
      for (BaggagePropagationConfig config : configs) {
        builder.add(config);
      }
    }
    if (customizers != null) {
      for (BaggagePropagationCustomizer customizer : customizers) customizer.customize(builder);
    }
    return builder.build();
  }

  @Override public Class<? extends Propagation.Factory> getObjectType() {
    return Propagation.Factory.class;
  }

  @Override public boolean isSingleton() {
    return true;
  }

  public void setPropagationFactory(Propagation.Factory propagationFactory) {
    this.propagationFactory = propagationFactory;
  }

  public void setConfigs(List<BaggagePropagationConfig> configs) {
    this.configs = configs;
  }

  public void setCustomizers(List<BaggagePropagationCustomizer> customizers) {
    this.customizers = customizers;
  }
}
