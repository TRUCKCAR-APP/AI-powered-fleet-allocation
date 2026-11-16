package escuelaing.edu.co.truckcar.fleet_allocation.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AllocationCandidateResponse {
    private String vehiclePlate;
    private String driverId;
    private String driverName;
    private String truckType;
    private Double deadheadDistanceKm;
    private Double capacityUtilizationPercent;
    /** Puntaje total sobre 100: la suma de las tres contribuciones de abajo. */
    private Double score;
    /** Puntos por cercanía a la recogida (peso 50 %). */
    private Double proximityPoints;
    /** Puntos por aprovechamiento de la capacidad del camión (peso 30 %). */
    private Double capacityPoints;
    /** Puntos por la calificación del conductor (peso 20 %). */
    private Double ratingPoints;
    private Boolean eligible;
    private String reason;
}