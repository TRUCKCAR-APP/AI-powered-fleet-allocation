package escuelaing.edu.co.truckcar.fleet_allocation.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Sobre común de los eventos. Lector tolerante: un campo nuevo del productor no rompe el consumo. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventEnvelope<T> {

    private UUID eventId;
    private String eventType;
    private Instant occurredAt;
    private UUID correlationId;
    private int version;
    private String producer;
    private T payload;
}
