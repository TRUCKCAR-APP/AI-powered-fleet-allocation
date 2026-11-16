package escuelaing.edu.co.truckcar.fleet_allocation.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payload de los eventos de Load-Allocation. Lector tolerante: Load-Allocation agrega campos (cancellationReason,
 * proposedBy...) y un campo desconocido no puede mandar el evento a la cola de muertos ni frenar el motor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreightLoadEventPayload {

    private Long loadId;
    private String generatorCompanyId;
    private String cargoDescription;
    private Double weightTons;
    private String requiredTruckType;
    private String originCity;
    private String destinationCity;
    private BigDecimal offeredBudget;
    private String loadStatus;
    private String assignmentStatus;
    private String assignedDriverId;
    private String assignedDriverName;
    private String assignedVehiclePlate;
    private Integer assignmentAttempts;
    private List<String> excludedDriverIds;
}
