/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.baggage;

import brave.Tracing;
import brave.baggage.BaggagePropagationConfig.SingleBaggageField;
import brave.internal.baggage.BaggageContext;
import brave.internal.baggage.BaggageFields;
import brave.internal.baggage.ExtraBaggageContext;
import brave.propagation.B3Propagation;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BaggageFieldTest {
  static final BaggageField REQUEST_ID = BaggageField.create("requestId");
  static final BaggageField AMZN_TRACE_ID = BaggageField.create("x-amzn-trace-id");

  Propagation.Factory factory = BaggagePropagation.newFactoryBuilder(B3Propagation.FACTORY)
    .add(SingleBaggageField.newBuilder(REQUEST_ID).addKeyName("x-vcap-request-id").build())
    .add(SingleBaggageField.remote(AMZN_TRACE_ID)).build();
  Propagation<String> propagation = factory.get();
  Extractor<Map<String, String>> extractor = propagation.extractor(Map::get);

  TraceContextOrSamplingFlags emptyExtraction = extractor.extract(Collections.emptyMap());
  String requestId = "abcdef";
  TraceContextOrSamplingFlags requestIdExtraction =
    extractor.extract(Collections.singletonMap("x-vcap-request-id", requestId));

  TraceContext context = TraceContext.newBuilder().traceId(1).spanId(2).build();
  TraceContext emptyContext = factory.decorate(context);
  TraceContextOrSamplingFlags extraction = TraceContextOrSamplingFlags.create(emptyContext);
  TraceContext requestIdContext =
    context.toBuilder().addExtra(requestIdExtraction.extra().get(0)).build();

  @Test void internalStorage() {
    assertThat(BaggageField.create("foo").context)
      .isSameAs(ExtraBaggageContext.get());

    BaggageContext context = mock(BaggageContext.class);
    assertThat(new BaggageField("context", context).context)
      .isSameAs(context);
  }

  @Test void getByName_doesntExist() {
    assertThat(BaggageField.getByName(emptyContext, "robots")).isNull();
    assertThat(BaggageField.getByName("robots")).isNull();

    try (Tracing tracing = Tracing.newBuilder().build();
         Scope scope = tracing.currentTraceContext().newScope(null)) {
      assertThat(BaggageField.getByName(REQUEST_ID.name())).isNull();
    }
  }

  @Test void getByName() {
    assertThat(BaggageField.getByName(emptyContext, REQUEST_ID.name()))
      .isSameAs(REQUEST_ID);

    try (Tracing tracing = Tracing.newBuilder().build();
         Scope scope = tracing.currentTraceContext().newScope(emptyContext)) {
      assertThat(BaggageField.getByName(REQUEST_ID.name()))
        .isSameAs(REQUEST_ID);
    }
  }

  @Test void getByName_extracted() {
    assertThat(BaggageField.getByName(emptyExtraction, REQUEST_ID.name()))
      .isSameAs(REQUEST_ID)
      .isSameAs(BaggageField.getByName(extraction, REQUEST_ID.name()));
  }

  @Test void getByName_context_null() {
    // permits unguarded use of CurrentTraceContext.get()
    assertThat(BaggageField.getByName((TraceContext) null, "foo"))
      .isNull();
  }

  @Test void getByName_invalid() {
    assertThatThrownBy(() -> BaggageField.getByName(context, ""))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BaggageField.getByName(context, "    "))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void trimsName() {
    assertThat(BaggageField.create(" x-foo  ").name())
      .isEqualTo("x-foo");
  }

  @Test void create_invalid() {
    assertThatThrownBy(() -> BaggageField.create(null))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> BaggageField.create(""))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BaggageField.create("    "))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void getValue_current_exists() {
    try (Tracing t = Tracing.newBuilder().propagationFactory(factory).build();
         Scope scope = t.currentTraceContext().newScope(requestIdContext)) {
      assertThat(REQUEST_ID.getValue())
        .isEqualTo(requestId);
    }
  }

  @Test void getValue_current_doesntExist() {
    try (Tracing t = Tracing.newBuilder().propagationFactory(factory).build();
         Scope scope = t.currentTraceContext().newScope(requestIdContext)) {
      assertThat(AMZN_TRACE_ID.getValue())
        .isNull();
    }
  }

  @Test void getValue_current_nothingCurrent() {
    assertThat(AMZN_TRACE_ID.getValue())
      .isNull();
  }

  @Test void getValue_context_exists() {
    assertThat(REQUEST_ID.getValue(requestIdContext))
      .isEqualTo(requestId);
  }

  @Test void getValue_context_doesntExist() {
    assertThat(AMZN_TRACE_ID.getValue(requestIdContext))
      .isNull();
    assertThat(AMZN_TRACE_ID.getValue(emptyContext))
      .isNull();
    assertThat(AMZN_TRACE_ID.getValue(context))
      .isNull();
  }

  @Test void getValue_context_null() {
    // permits unguarded use of CurrentTraceContext.get()
    assertThat(REQUEST_ID.getValue((TraceContext) null))
      .isNull();
  }

  @Test void getValue_extracted_exists() {
    assertThat(REQUEST_ID.getValue(requestIdExtraction))
      .isEqualTo(requestId);
  }

  @Test void getValue_extracted_doesntExist() {
    assertThat(AMZN_TRACE_ID.getValue(requestIdExtraction))
      .isNull();
    assertThat(AMZN_TRACE_ID.getValue(emptyExtraction))
      .isNull();
    assertThat(AMZN_TRACE_ID.getValue(TraceContextOrSamplingFlags.EMPTY))
      .isNull();
  }

  @Test void getValue_extracted_invalid() {
    assertThatThrownBy(() -> REQUEST_ID.getValue((TraceContextOrSamplingFlags) null))
      .isInstanceOf(NullPointerException.class);
  }

  @Test void updateValue_current_exists() {
    try (Tracing t = Tracing.newBuilder().propagationFactory(factory).build();
         Scope scope = t.currentTraceContext().newScope(requestIdContext)) {
      REQUEST_ID.updateValue("12345");
      assertThat(REQUEST_ID.getValue())
        .isEqualTo("12345");
    }
  }

  @Test void updateValue_current_doesntExist() {
    try (Tracing t = Tracing.newBuilder().propagationFactory(factory).build();
         Scope scope = t.currentTraceContext().newScope(requestIdContext)) {
      AMZN_TRACE_ID.updateValue("12345");
      assertThat(AMZN_TRACE_ID.getValue())
        .isEqualTo("12345");
    }
  }

  @Test void updateValue_current_nothingCurrent() {
    AMZN_TRACE_ID.updateValue("12345");
    assertThat(AMZN_TRACE_ID.getValue())
      .isNull();
  }

  @Test void updateValue_context_exists() {
    REQUEST_ID.updateValue(requestIdContext, "12345");
    assertThat(REQUEST_ID.getValue(requestIdContext))
      .isEqualTo("12345");
  }

  @Test void updateValue_context_doesntExist() {
    AMZN_TRACE_ID.updateValue(requestIdContext, "12345");
    assertThat(AMZN_TRACE_ID.getValue(requestIdContext))
      .isEqualTo("12345");

    AMZN_TRACE_ID.updateValue(emptyContext, "12345");
    assertThat(AMZN_TRACE_ID.getValue(emptyContext))
      .isEqualTo("12345");

    AMZN_TRACE_ID.updateValue(context, "12345");
    assertThat(AMZN_TRACE_ID.getValue(context))
      .isNull();
  }

  @Test void updateValue_context_null() {
    // permits unguarded use of CurrentTraceContext.get()
    REQUEST_ID.updateValue((TraceContext) null, null);
  }

  @Test void updateValue_extracted_exists() {
    REQUEST_ID.updateValue(requestIdExtraction, "12345");
    assertThat(REQUEST_ID.getValue(requestIdExtraction))
      .isEqualTo("12345");
  }

  @Test void updateValue_extracted_doesntExist() {
    AMZN_TRACE_ID.updateValue(requestIdExtraction, "12345");
    assertThat(AMZN_TRACE_ID.getValue(requestIdExtraction))
      .isEqualTo("12345");

    AMZN_TRACE_ID.updateValue(emptyExtraction, "12345");
    assertThat(AMZN_TRACE_ID.getValue(emptyExtraction))
      .isEqualTo("12345");

    AMZN_TRACE_ID.updateValue(TraceContextOrSamplingFlags.EMPTY, "12345");
  }

  @Test void updateValue_extracted_invalid() {
    assertThatThrownBy(() -> REQUEST_ID.updateValue((TraceContextOrSamplingFlags) null, null))
      .isInstanceOf(NullPointerException.class);
  }

  @Test void toString_onlyHasName() {
    assertThat(BaggageField.create("Foo"))
      .hasToString("BaggageField{Foo}"); // case preserved as that's the field name
  }

  /**
   * Ensures only lower-case name comparison is used in equals and hashCode. This allows {@link
   * BaggagePropagation} to deduplicate and {@link BaggageFields} to use these as keys.
   */
  @Test void equalsAndHashCode() {
    // same field are equivalent
    BaggageField field = BaggageField.create("foo");
    assertThat(field).isEqualTo(field);
    assertThat(field).hasSameHashCodeAs(field);

    // different case format is equivalent
    BaggageField sameName = BaggageField.create("fOo");
    assertThat(field).isEqualTo(sameName);
    assertThat(sameName).isEqualTo(field);
    assertThat(field).hasSameHashCodeAs(sameName);

    // different values are not equivalent
    BaggageField differentValue = BaggageField.create("different");
    assertThat(field).isNotEqualTo(differentValue);
    assertThat(differentValue).isNotEqualTo(field);
    assertThat(field.hashCode()).isNotEqualTo(differentValue.hashCode());
  }
}
