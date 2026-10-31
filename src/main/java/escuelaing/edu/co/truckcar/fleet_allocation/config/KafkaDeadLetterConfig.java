package escuelaing.edu.co.truckcar.fleet_allocation.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.Map;

/**
 * Manejo de errores de los consumidores: 3 reintentos con backoff exponencial (1 s, 2 s, 4 s) y después el registro va
 * a &lt;tópico&gt;.DLT, así un mensaje que falla no bloquea el offset del grupo. Un mensaje mal formado
 * (IllegalArgumentException, JsonProcessingException, también como causa) va al DLT sin reintentar.
 * <p>
 * Los consumidores leen el valor como String: el DLT se publica con StringSerializer para conservar el mensaje original
 * byte a byte (el KafkaTemplate de la aplicación serializa a JSON y lo escaparía).
 */
@Slf4j
@Configuration
public class KafkaDeadLetterConfig implements DisposableBean {

    public static final String DLT_SUFFIX = ".DLT";

    private final DefaultKafkaProducerFactory<String, String> deadLetterProducerFactory;

    public KafkaDeadLetterConfig(@Value("${spring.kafka.bootstrap-servers:127.0.0.1:9092}") String bootstrapServers) {
        this.deadLetterProducerFactory = new DefaultKafkaProducerFactory<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                        ProducerConfig.ACKS_CONFIG, "all"),
                new StringSerializer(), new StringSerializer());
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                new KafkaTemplate<>(deadLetterProducerFactory),
                (record, exception) -> {
                    log.error("Mensaje de {}-{}@{} enviado a {}{}: {}", record.topic(), record.partition(),
                            record.offset(), record.topic(), DLT_SUFFIX, exception.getMessage());
                    // Partición -1: la elige el productor, aunque el DLT tenga menos particiones que el original.
                    return new TopicPartition(record.topic() + DLT_SUFFIX, -1);
                });
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class, JsonProcessingException.class);
        return errorHandler;
    }

    @Override
    public void destroy() {
        deadLetterProducerFactory.destroy();
    }
}
