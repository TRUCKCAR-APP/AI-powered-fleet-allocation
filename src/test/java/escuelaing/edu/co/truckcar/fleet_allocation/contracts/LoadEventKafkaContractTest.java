package escuelaing.edu.co.truckcar.fleet_allocation.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import escuelaing.edu.co.truckcar.fleet_allocation.config.JacksonConfig;
import escuelaing.edu.co.truckcar.fleet_allocation.listener.DriverAvailabilityEventListener;
import escuelaing.edu.co.truckcar.fleet_allocation.listener.FreightLoadEventListener;
import escuelaing.edu.co.truckcar.fleet_allocation.service.IFleetAllocationService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Contrato con Load-Allocation (productor): contracts/load-allocation.load-event.v1.json es una copia del fixture que
 * Load-Allocation verifica contra su outbox. AI-fleet lo consume con su ObjectMapper real, también con campos que
 * todavía no conoce (el fallo de #12), y por un Kafka real (Testcontainers; sin Docker se omite esa parte).
 */
class LoadEventKafkaContractTest {

    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    private ObjectNode loadEvent(String type) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/contracts/load-allocation.load-event.v1.json")) {
            ObjectNode event = (ObjectNode) mapper.readTree(in);
            event.put("eventType", type).put("campoFuturoDelSobre", "x");
            ((ObjectNode) event.get("payload")).put("campoFuturo", 1).putObject("objetoFuturo").put("a", 1);
            return event;
        }
    }

    @Test
    void aPublishedLoadWithFieldsWeDoNotKnowStartsTheMatching() throws Exception {
        IFleetAllocationService service = mock(IFleetAllocationService.class);
        ObjectNode event = loadEvent("load.published");

        new FreightLoadEventListener(service, mapper).handleLoadPublished(mapper.writeValueAsString(event));

        verify(service).processAutomaticAllocationForLoad(95L, UUID.fromString(event.get("correlationId").asText()));
    }

    @Test
    void aRejectionExcludesTheDiscardedDrivers() throws Exception {
        IFleetAllocationService service = mock(IFleetAllocationService.class);
        ObjectNode event = loadEvent("assignment.rejected");

        new FreightLoadEventListener(service, mapper).handleLoadPublished(mapper.writeValueAsString(event));

        verify(service).processAutomaticReallocationForLoad(eq(95L), eq(Set.of("driver-0", "driver-1")),
                eq("assignment.rejected"), any());
    }

    @Test
    void anAvailabilityEventFromFleetsDriversWithNewFieldsIsAccepted() {
        assertThatCode(() -> new DriverAvailabilityEventListener(mapper).handleDriverAvailability("""
                {"eventId":"%s","eventType":"driver.availability.changed","occurredAt":"2026-11-16T18:00:00Z",
                 "correlationId":"%s","version":1,"producer":"fleets-drivers","nuevo":true,
                 "payload":{"driverId":"ana","vehiclePlate":"TDK-410","status":"ACTIVO","drivingHoursToday":0.5,
                            "remainingDrivingHours":8.5,"campoFuturo":"x"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID()))).doesNotThrowAnyException();
    }

    /** La misma cadena por un broker real, con la configuración de producción (StringDeserializer + JacksonConfig). */
    @Nested
    @Testcontainers(disabledWithoutDocker = true)
    class ThroughARealKafka {

        @Container
        static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

        @Test
        void loadAllocationsEventCrossesKafkaAndReachesTheEngine() throws Exception {
            String topic = "truckcar.events.load-allocation";
            ObjectNode event = loadEvent("load.published");
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
                producer.send(new ProducerRecord<>(topic, "95", mapper.writeValueAsString(event))).get();
            }
            IFleetAllocationService service = mock(IFleetAllocationService.class);
            FreightLoadEventListener listener = new FreightLoadEventListener(service, mapper);
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, "fleet-allocation-group",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
                consumer.subscribe(List.of(topic));
                ConsumerRecords<String, String> records = ConsumerRecords.empty();
                for (int i = 0; i < 20 && records.isEmpty(); i++) {
                    records = consumer.poll(Duration.ofMillis(500));
                }
                assertThat(records.count()).isEqualTo(1);
                records.forEach(record -> listener.handleLoadPublished(record.value()));
            }
            verify(service).processAutomaticAllocationForLoad(eq(95L), any());
        }
    }
}
