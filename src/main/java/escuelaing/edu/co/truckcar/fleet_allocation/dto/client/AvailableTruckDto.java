package escuelaing.edu.co.truckcar.fleet_allocation.dto.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailableTruckDto {
    private String plate;
    private String truckType;
    /** Capacidad disponible (capacidad del vehículo menos lo que lleva); null si el vehículo no la tiene registrada. */
    private Double capacityTons;
    /** null = sin información (registros anteriores): no descarta. false = RUNT vencido: no se le asigna carga. */
    private Boolean runtValid;
    private Boolean coldChainCertified;
    private String driverId;
    private String driverName;
    private Double currentLatitude;
    private Double currentLongitude;
    private Double rating;
    private Boolean isAvailable;
    /** Si no es null, el conductor ya tiene una carga activa o una propuesta vigente y no es elegible. */
    private String busyReason;
    /** Cuenta verificada por un administrador en user-identity (fleets-drivers accountVerified). */
    private Boolean accountVerified;
    /** Altura total del camión (gálibo) y tara: con la carga a bordo dan lo que se valida contra la ruta. */
    private Double heightMeters;
    private Double tareWeightTons;
    private Double onBoardTons;
    /** Motivo de incompatibilidad con la ruta de la carga (gálibo o peso); null si es apta o no se pudo verificar. */
    private String routeRestrictionReason;
}