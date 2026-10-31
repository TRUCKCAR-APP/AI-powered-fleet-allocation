package escuelaing.edu.co.truckcar.fleet_allocation.config;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Trazas y métricas de Kafka y filtro de observaciones de infraestructura.
 * <p>
 * Los KafkaTemplate y las fábricas de listeners de este servicio se declaran a mano, así que las propiedades
 * spring.kafka.*.observation-enabled no les llegan: el post-procesador activa la observación en todos (el productor
 * escribe traceparent y X-Correlation-Id en las cabeceras de cada registro y el consumidor continúa esa traza) y
 * registra las métricas del cliente Kafka (records enviados/consumidos, lag) en Micrometer.
 * <p>
 * Cada span lleva además el atributo X-Correlation-Id (baggage que llega del api-gateway por HTTP o en las cabeceras de
 * Kafka): en Tempo se encuentra la traza completa de una petición buscando por ese valor.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    static BeanPostProcessor kafkaObservationPostProcessor(ObjectProvider<MeterRegistry> meterRegistry) {
        return new KafkaObservationPostProcessor(meterRegistry);
    }

    /** Activa @Observed (matching de FleetAllocationService): un span y un timer por ejecución. */
    @Bean
    ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    @Bean
    SpanProcessor correlationIdSpanProcessor() {
        return new CorrelationIdSpanProcessor();
    }

    /**
     * Los spans de entrada (petición HTTP, registro Kafka recibido) nacen antes de que su baggage quede activo, así que
     * CorrelationIdSpanProcessor no los alcanza: el X-Correlation-Id se toma directamente de la cabecera.
     */
    @Bean
    ObservationFilter correlationIdOfEntrySpans() {
        return context -> {
            String correlationId = null;
            if (context instanceof ServerRequestObservationContext request) {
                correlationId = request.getCarrier().getHeader(CorrelationIdSpanProcessor.CORRELATION_ID);
            } else if (context instanceof KafkaRecordReceiverContext received) {
                Header header = received.getCarrier().headers().lastHeader(CorrelationIdSpanProcessor.CORRELATION_ID);
                correlationId = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
            }
            if (correlationId != null && !correlationId.isBlank()) {
                // Alta cardinalidad: va a la traza, nunca como etiqueta de métrica.
                context.addHighCardinalityKeyValue(KeyValue.of(CorrelationIdSpanProcessor.CORRELATION_ID, correlationId));
            }
            return context;
        };
    }

    /**
     * Sin trazas para el scrape de Prometheus y los health checks (/actuator/**) ni para las tareas programadas que
     * sondean cada pocos segundos (outbox, vencimientos): generarían una traza vacía por ciclo.
     */
    @Bean
    ObservationPredicate skipInfrastructureObservations() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext request) {
                return !request.getCarrier().getRequestURI().startsWith("/actuator");
            }
            return !"tasks.scheduled.execution".equals(name);
        };
    }

    /**
     * Copia el baggage X-Correlation-Id del contexto padre a cada span al iniciarlo. management.tracing.baggage.tag-fields
     * no sirve con el bridge de OpenTelemetry: solo etiqueta el span cuando el código lee o crea el baggage por la API.
     */
    static final class CorrelationIdSpanProcessor implements SpanProcessor {

        static final String CORRELATION_ID = "X-Correlation-Id";

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            String correlationId = Baggage.fromContext(parentContext).getEntryValue(CORRELATION_ID);
            if (correlationId != null && !correlationId.isBlank()) {
                span.setAttribute(CORRELATION_ID, correlationId);
            }
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            // Sin acciones al terminar.
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }
    }

    static final class KafkaObservationPostProcessor implements BeanPostProcessor {

        private final ObjectProvider<MeterRegistry> meterRegistry;
        private final Set<Object> instrumented = Collections.newSetFromMap(new IdentityHashMap<>());

        KafkaObservationPostProcessor(ObjectProvider<MeterRegistry> meterRegistry) {
            this.meterRegistry = meterRegistry;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof KafkaTemplate<?, ?> template) {
                template.setObservationEnabled(true);
                instrument(template.getProducerFactory());
            } else if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
                factory.getContainerProperties().setObservationEnabled(true);
                instrument(factory.getConsumerFactory());
            }
            return bean;
        }

        private void instrument(Object factory) {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry == null || factory == null || !instrumented.add(factory)) {
                return;
            }
            if (factory instanceof DefaultKafkaProducerFactory<?, ?> producers) {
                addProducerMetrics(producers, registry);
            } else if (factory instanceof DefaultKafkaConsumerFactory<?, ?> consumers) {
                addConsumerMetrics(consumers, registry);
            }
        }

        // Las fábricas autoconfiguradas por Spring Boot ya traen el listener de Micrometer: no se duplica.
        private static <K, V> void addProducerMetrics(DefaultKafkaProducerFactory<K, V> factory, MeterRegistry registry) {
            if (factory.getListeners().stream().noneMatch(MicrometerProducerListener.class::isInstance)) {
                factory.addListener(new MicrometerProducerListener<>(registry));
            }
        }

        private static <K, V> void addConsumerMetrics(DefaultKafkaConsumerFactory<K, V> factory, MeterRegistry registry) {
            if (factory.getListeners().stream().noneMatch(MicrometerConsumerListener.class::isInstance)) {
                factory.addListener(new MicrometerConsumerListener<>(registry));
            }
        }
    }
}
