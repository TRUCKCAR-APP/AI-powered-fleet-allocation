package escuelaing.edu.co.truckcar.fleet_allocation.dto.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Contrato de GET /api/v1/fleets-drivers/active en fleets-drivers (DriverVehicleStateResponse).
 * Solo se mapean los campos que usa el motor de scoring.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FleetDriverStateDto {

    private String driverId;
    private String driverName;
    private Double rating;
    private String vehiclePlate;
    private String vehicleType;
    /** Carga que lleva a bordo ahora (no es la capacidad). */
    private Double netCargoWeightTons;
    /** Capacidad de carga del vehículo; sin ella no se puede saber si la carga cabe. */
    private Double maxPayloadTons;
    private Boolean runtValid;
    private Boolean coldChainCertified;
    private boolean restRequiredAlert;
    private Double currentLatitude;
    private Double currentLongitude;
    private String status;
    private boolean onTrip;
    private Long currentLoadId;
    private String currentLoadStatus;

    public boolean isAvailableForAllocation() {
        return "ACTIVO".equalsIgnoreCase(status) && !restRequiredAlert;
    }

    /**
     * Lo que aún cabe: capacidad del vehículo menos la carga a bordo. Antes se tomaba netCargoWeightTons (la carga a
     * bordo) como capacidad, así que un camión vacío nunca era apto y uno lleno parecía tener espacio.
     */
    public Double availablePayloadTons() {
        if (maxPayloadTons == null) {
            return null;
        }
        double onBoard = netCargoWeightTons == null ? 0.0 : netCargoWeightTons;
        return Math.max(0.0, maxPayloadTons - onBoard);
    }

    public AvailableTruckDto toAvailableTruck() {
        return AvailableTruckDto.builder()
                .driverId(driverId)
                .driverName(driverName)
                .rating(rating)
                .plate(vehiclePlate)
                .truckType(vehicleType)
                .capacityTons(availablePayloadTons())
                .runtValid(runtValid)
                .coldChainCertified(coldChainCertified)
                .currentLatitude(currentLatitude)
                .currentLongitude(currentLongitude)
                .isAvailable(isAvailableForAllocation())
                .busyReason(onTrip ? "Conductor ocupado con la carga #" + currentLoadId + " (" + currentLoadStatus + ")" : null)
                .build();
    }
}
