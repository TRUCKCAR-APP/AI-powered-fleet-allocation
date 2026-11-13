package escuelaing.edu.co.truckcar.fleet_allocation.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.event.EventEnvelope;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.event.FreightLoadEventPayload;
import escuelaing.edu.co.truckcar.fleet_allocation.service.IFleetAllocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class FreightLoadEventListener {

    /** Eventos de Load-Allocation que dejan libre al conductor asignado. */
    private static final Set<String> DRIVER_RELEASE_EVENTS = Set.of("trip.delivered", "load.cancelled");

    private final IFleetAllocationService allocationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${truckcar.kafka.topic.load-events:truckcar.events.load-allocation}", groupId = "fleet-allocation-group")
    public void handleLoadPublished(String message) {
        try {
            EventEnvelope<FreightLoadEventPayload> event = objectMapper.readValue(
                    message,
                    objectMapper.getTypeFactory().constructParametricType(
                            EventEnvelope.class,
                            FreightLoadEventPayload.class
                    )
            );
            if ("load.published".equals(event.getEventType()) && event.getPayload() != null) {
                log.info("Evento Kafka de nueva carga recibido para matching automático: Carga #{}",
                        event.getPayload().getLoadId());
                allocationService.processAutomaticAllocationForLoad(event.getPayload().getLoadId(),
                        event.getCorrelationId());
            } else if (("assignment.rejected".equals(event.getEventType())
                    || "assignment.timeout".equals(event.getEventType()))
                    && event.getPayload() != null
                    && event.getPayload().getLoadId() != null) {
                // Se excluyen todos los conductores descartados por la carga, no solo el último, para evitar ciclos A→B→A.
                Set<String> excludedDriverIds = new LinkedHashSet<>();
                if (event.getPayload().getExcludedDriverIds() != null) {
                    excludedDriverIds.addAll(event.getPayload().getExcludedDriverIds());
                }
                if (event.getPayload().getAssignedDriverId() != null) {
                    excludedDriverIds.add(event.getPayload().getAssignedDriverId());
                }
                log.info("Reasignación solicitada para carga #{} por {}; se excluyen conductores {}",
                        event.getPayload().getLoadId(), event.getEventType(), excludedDriverIds);
                allocationService.processAutomaticReallocationForLoad(
                        event.getPayload().getLoadId(), excludedDriverIds, event.getEventType(),
                        event.getCorrelationId());
            } else if (DRIVER_RELEASE_EVENTS.contains(event.getEventType())
                    && event.getPayload() != null
                    && event.getPayload().getAssignedDriverId() != null) {
                // El conductor quedó libre: las cargas que esperaban conductor se vuelven a evaluar.
                log.info("Conductor {} liberado por {} de la carga #{}; se reintentan las cargas en espera",
                        event.getPayload().getAssignedDriverId(), event.getEventType(), event.getPayload().getLoadId());
                allocationService.retryWaitingLoads(event.getEventType());
            }
        } catch (Exception e) {
            log.error("No se pudo procesar evento de carga con sobre común", e);
            throw new IllegalStateException("Evento de carga inválido; se requiere reintento o DLQ", e);
        }
    }
}