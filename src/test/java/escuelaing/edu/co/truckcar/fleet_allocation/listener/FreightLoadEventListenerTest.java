package escuelaing.edu.co.truckcar.fleet_allocation.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.event.EventEnvelope;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.event.FreightLoadEventPayload;
import escuelaing.edu.co.truckcar.fleet_allocation.service.IFleetAllocationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.verify;

class FreightLoadEventListenerTest {

    @Test
    void rejectedAssignmentTriggersReallocationAndExcludesDriver() throws Exception {
        IFleetAllocationService service = Mockito.mock(IFleetAllocationService.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        UUID correlationId = UUID.randomUUID();
        FreightLoadEventListener listener = new FreightLoadEventListener(service, mapper);
        EventEnvelope<FreightLoadEventPayload> event = EventEnvelope.<FreightLoadEventPayload>builder()
                .eventId(UUID.randomUUID())
                .eventType("assignment.rejected")
                .occurredAt(Instant.parse("2026-10-10T23:00:00Z"))
                .correlationId(correlationId)
                .version(1)
                .producer("load-allocation")
                .payload(FreightLoadEventPayload.builder()
                        .loadId(42L)
                        .assignedDriverId("driver-rejected")
                        .excludedDriverIds(List.of("driver-earlier", "driver-rejected"))
                        .build())
                .build();

        listener.handleLoadPublished(mapper.writeValueAsString(event));

        verify(service).processAutomaticReallocationForLoad(
                42L, Set.of("driver-earlier", "driver-rejected"), "assignment.rejected", correlationId);
    }

    /**
     * Regresión: Load-Allocation agregó cancellationReason y proposedBy al payload, y el ObjectMapper de la aplicación
     * (JacksonConfig, estricto) mandaba todos los eventos de carga a la cola de muertos: el motor dejó de proponer.
     */
    @Test
    void fieldsAddedByLoadAllocationDoNotStopTheEngine() throws Exception {
        IFleetAllocationService service = Mockito.mock(IFleetAllocationService.class);
        ObjectMapper appMapper = new escuelaing.edu.co.truckcar.fleet_allocation.config.JacksonConfig().objectMapper();
        FreightLoadEventListener listener = new FreightLoadEventListener(service, appMapper);
        UUID correlationId = UUID.randomUUID();

        listener.handleLoadPublished("""
                {"eventId":"%s","eventType":"load.published","occurredAt":"2026-11-16T18:31:01Z","correlationId":"%s",
                 "version":1,"producer":"load-allocation","campoNuevoDelSobre":"x",
                 "payload":{"loadId":95,"weightTons":5.0,"requiredTruckType":"TURBO","loadStatus":"AVAILABLE",
                            "excludedDriverIds":[],"cancellationReason":null,"proposedBy":null,"otroCampoFuturo":{"a":1}}}
                """.formatted(UUID.randomUUID(), correlationId));

        verify(service).processAutomaticAllocationForLoad(95L, correlationId);
    }

    private static String event(ObjectMapper mapper, String type, String assignedDriverId) throws Exception {
        return mapper.writeValueAsString(EventEnvelope.<FreightLoadEventPayload>builder()
                .eventId(UUID.randomUUID())
                .eventType(type)
                .occurredAt(Instant.parse("2026-10-11T23:00:00Z"))
                .correlationId(UUID.randomUUID())
                .version(1)
                .producer("load-allocation")
                .payload(FreightLoadEventPayload.builder().loadId(7L).assignedDriverId(assignedDriverId).build())
                .build());
    }

    @Test
    void deliveryOrCancellationOfAnAssignedLoadRetriesWaitingLoads() throws Exception {
        IFleetAllocationService service = Mockito.mock(IFleetAllocationService.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        FreightLoadEventListener listener = new FreightLoadEventListener(service, mapper);

        listener.handleLoadPublished(event(mapper, "trip.delivered", "driver-ana"));
        listener.handleLoadPublished(event(mapper, "load.cancelled", "driver-ana"));

        verify(service).retryWaitingLoads("trip.delivered");
        verify(service).retryWaitingLoads("load.cancelled");
    }

    @Test
    void cancellingALoadWithoutDriverDoesNotRetry() throws Exception {
        IFleetAllocationService service = Mockito.mock(IFleetAllocationService.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        FreightLoadEventListener listener = new FreightLoadEventListener(service, mapper);

        listener.handleLoadPublished(event(mapper, "load.cancelled", null));
        listener.handleLoadPublished(event(mapper, "trip.started", "driver-ana"));

        verify(service, Mockito.never()).retryWaitingLoads(Mockito.anyString());
    }
}
