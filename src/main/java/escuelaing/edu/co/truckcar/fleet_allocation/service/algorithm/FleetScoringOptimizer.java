package escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.AvailableTruckDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationCandidateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FleetScoringOptimizer {

    private final HaversineDistanceCalculator distanceCalculator;

    private static final double WEIGHT_PROXIMITY = 0.50;
    private static final double WEIGHT_CAPACITY = 0.30;
    private static final double WEIGHT_RATING = 0.20;
    /**
     * Cercanía con decaimiento exponencial: 1 en el origen, 0,61 a 100 km, 0,22 a 300 km. Con el corte lineal anterior,
     * todo camión a más de 300 km empataba en 0 y el de 310 km valía lo mismo que el de 900 km.
     */
    private static final double PROXIMITY_DECAY_KM = 200.0;

    public AllocationCandidateResponse evaluateTruck(AvailableTruckDto truck, FreightLoadDto load) {
        if (truck.getBusyReason() != null) {
            return AllocationCandidateResponse.builder()
                    .vehiclePlate(truck.getPlate())
                    .driverId(truck.getDriverId())
                    .driverName(truck.getDriverName())
                    .truckType(truck.getTruckType())
                    .eligible(false)
                    .score(0.0)
                    .reason(truck.getBusyReason())
                    .build();
        }

        if (Boolean.FALSE.equals(truck.getRuntValid())) {
            return ineligible(truck, "RUNT no vigente: el vehículo no puede recibir cargas");
        }

        if ("REFRIGERADO".equalsIgnoreCase(load.getRequiredTruckType()) && !Boolean.TRUE.equals(truck.getColdChainCertified())) {
            return ineligible(truck, "La carga requiere cadena de frío y el conductor no está certificado");
        }

        if (truck.getCapacityTons() == null) {
            return ineligible(truck, "Capacidad de carga del vehículo sin registrar");
        }

        if (truck.getCapacityTons() < load.getWeightTons()) {
            return AllocationCandidateResponse.builder()
                    .vehiclePlate(truck.getPlate())
                    .driverId(truck.getDriverId())
                    .driverName(truck.getDriverName())
                    .truckType(truck.getTruckType())
                    .eligible(false)
                    .score(0.0)
                    .reason("Capacidad insuficiente (" + (truck.getCapacityTons() != null ? truck.getCapacityTons() : 0.0) + " Tn < " + load.getWeightTons() + " Tn requeridas)")
                    .build();
        }

        if (load.getRequiredTruckType() != null && !load.getRequiredTruckType().equalsIgnoreCase(truck.getTruckType())) {
            return AllocationCandidateResponse.builder()
                    .vehiclePlate(truck.getPlate())
                    .driverId(truck.getDriverId())
                    .driverName(truck.getDriverName())
                    .truckType(truck.getTruckType())
                    .eligible(false)
                    .score(0.0)
                    .reason("Tipo de vehículo no coincide (" + truck.getTruckType() + " != " + load.getRequiredTruckType() + ")")
                    .build();
        }

        double distanceKm = distanceCalculator.calculateDistanceKm(
                truck.getCurrentLatitude(), truck.getCurrentLongitude(),
                load.getOriginLatitude(), load.getOriginLongitude()
        );

        double proximityScore = distanceKm == Double.MAX_VALUE ? 0.0 : Math.exp(-distanceKm / PROXIMITY_DECAY_KM);
        double capacityEfficiency = load.getWeightTons() / truck.getCapacityTons();
        double ratingFactor = (truck.getRating() != null ? truck.getRating() : 4.0) / 5.0;

        double finalScore = (proximityScore * WEIGHT_PROXIMITY)
                + (capacityEfficiency * WEIGHT_CAPACITY)
                + (ratingFactor * WEIGHT_RATING);

        double roundedScore = Math.round(finalScore * 1000.0) / 10.0;
        Double deadhead = distanceKm == Double.MAX_VALUE ? null : distanceKm;
        double roundedUtilization = Math.round(capacityEfficiency * 1000.0) / 10.0;

        return AllocationCandidateResponse.builder()
                .vehiclePlate(truck.getPlate())
                .driverId(truck.getDriverId())
                .driverName(truck.getDriverName())
                .truckType(truck.getTruckType())
                .deadheadDistanceKm(deadhead)
                .capacityUtilizationPercent(roundedUtilization)
                .score(roundedScore)
                .eligible(true)
                .reason("Candidato idóneo evaluado por modelo heurístico multicriterio")
                .build();
    }

    private static AllocationCandidateResponse ineligible(AvailableTruckDto truck, String reason) {
        return AllocationCandidateResponse.builder()
                .vehiclePlate(truck.getPlate())
                .driverId(truck.getDriverId())
                .driverName(truck.getDriverName())
                .truckType(truck.getTruckType())
                .eligible(false)
                .score(0.0)
                .reason(reason)
                .build();
    }

    /** Mejor puntaje primero; a igual puntaje, el más cercano y luego el id, para que el orden no dependa de Redis. */
    public static final java.util.Comparator<AllocationCandidateResponse> RANKING =
            java.util.Comparator.comparing(AllocationCandidateResponse::getScore, java.util.Comparator.reverseOrder())
                    .thenComparing(AllocationCandidateResponse::getDeadheadDistanceKm, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                    .thenComparing(AllocationCandidateResponse::getDriverId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
}
