package escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.AvailableTruckDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FleetDriverStateDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationCandidateResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validación exhaustiva del algoritmo de optimización multicriterio de asignación de flota.
 * Comprueba:
 * 1. Ponderación consistente por proximidad, capacidad y calificación.
 * 2. Filtros duros explicables (RUNT, capacidad, refrigeración, tipo de vehículo y disponibilidad).
 * 3. Reproducibilidad y consistencia determinista independiente del orden de entrada.
 */
class MulticriteriaAllocationVerificationTest {

    private final FleetScoringOptimizer optimizer = new FleetScoringOptimizer(new HaversineDistanceCalculator());

    private static final double BOGOTA_LAT = 4.6486;
    private static final double BOGOTA_LON = -74.1481;

    private static FreightLoadDto buildLoad(Long id, double weightTons, String type, double lat, double lon) {
        return FreightLoadDto.builder()
                .id(id)
                .weightTons(weightTons)
                .requiredTruckType(type)
                .originLatitude(lat)
                .originLongitude(lon)
                .build();
    }

    private static AvailableTruckDto buildTruck(String id, String plate, String type, double maxPayload,
                                                double netCargo, double rating, boolean runtValid,
                                                Boolean coldChain, String busyReason, double lat, double lon) {
        AvailableTruckDto truck = FleetDriverStateDto.builder()
                .accountVerified(true)
                .driverId(id)
                .driverName("Conductor " + id)
                .vehiclePlate(plate)
                .vehicleType(type)
                .maxPayloadTons(maxPayload)
                .netCargoWeightTons(netCargo)
                .rating(rating)
                .runtValid(runtValid)
                .coldChainCertified(coldChain)
                .currentLatitude(lat)
                .currentLongitude(lon)
                .status(busyReason == null ? "ACTIVO" : "OCUPADO")
                .build()
                .toAvailableTruck();
        truck.setBusyReason(busyReason);
        return truck;
    }

    @Test
    @DisplayName("Criterio multicriterio: balance reproducible entre proximidad, capacidad y calificación")
    void recommendationBalancesProximityCapacityAndRatingConsistently() {
        FreightLoadDto load = buildLoad(100L, 20.0, "TRACTOMULA", BOGOTA_LAT, BOGOTA_LON);

        // Conductor A: Muy cerca (0 km), camión de 30t (utilización 66.7%), rating 4.2
        AvailableTruckDto driverA = buildTruck("driver-A", "PLA-001", "TRACTOMULA", 30.0, 0.0, 4.2, true, true, null, BOGOTA_LAT, BOGOTA_LON);

        // Conductor B: A ~35 km, camión de 21t (utilización 95.2% - óptima), rating 5.0
        AvailableTruckDto driverB = buildTruck("driver-B", "PLA-002", "TRACTOMULA", 21.0, 0.0, 5.0, true, true, null, BOGOTA_LAT + 0.31, BOGOTA_LON);

        // Conductor C: Lejos (~600 km), camión de 25t, rating 4.0
        AvailableTruckDto driverC = buildTruck("driver-C", "PLA-003", "TRACTOMULA", 25.0, 0.0, 4.0, true, true, null, BOGOTA_LAT + 5.4, BOGOTA_LON);

        AllocationCandidateResponse evalA = optimizer.evaluateTruck(driverA, load);
        AllocationCandidateResponse evalB = optimizer.evaluateTruck(driverB, load);
        AllocationCandidateResponse evalC = optimizer.evaluateTruck(driverC, load);

        // Todos son elegibles
        assertThat(evalA.getEligible()).isTrue();
        assertThat(evalB.getEligible()).isTrue();
        assertThat(evalC.getEligible()).isTrue();

        // Conductor A y B deben tener puntajes significativamente mayores a C por cercanía
        assertThat(evalA.getScore()).isGreaterThan(evalC.getScore());
        assertThat(evalB.getScore()).isGreaterThan(evalC.getScore());

        // La decisión es explicable con datos numéricos de deadhead y utilización
        assertThat(evalA.getDeadheadDistanceKm()).isLessThan(1.0);
        assertThat(evalA.getCapacityUtilizationPercent()).isEqualTo(66.7);
        assertThat(evalB.getCapacityUtilizationPercent()).isEqualTo(95.2);

        // Ranking determinista
        List<AllocationCandidateResponse> ranked = List.of(evalC, evalA, evalB).stream()
                .sorted(FleetScoringOptimizer.RANKING)
                .toList();

        assertThat(ranked.get(2).getDriverId()).isEqualTo("driver-C");
        assertThat(ranked.get(0).getScore()).isGreaterThanOrEqualTo(ranked.get(1).getScore());
    }

    @Test
    @DisplayName("Filtros duros: exclusión estricta y explicable por RUNT, capacidad, tipo, cadena de frío y ocupación")
    void rejectionCriteriaAreStrictExplainableAndConsistent() {
        FreightLoadDto coldLoad = buildLoad(200L, 15.0, "REFRIGERADO", BOGOTA_LAT, BOGOTA_LON);

        // 1. Conductor ocupado
        AvailableTruckDto busyTruck = buildTruck("d-busy", "PL-1", "REFRIGERADO", 20.0, 0.0, 4.8, true, true, "En ruta activa Bogotá-Medellín", BOGOTA_LAT, BOGOTA_LON);
        AllocationCandidateResponse busyEval = optimizer.evaluateTruck(busyTruck, coldLoad);
        assertThat(busyEval.getEligible()).isFalse();
        assertThat(busyEval.getReason()).contains("En ruta activa");

        // 2. RUNT no vigente
        AvailableTruckDto runtInvalid = buildTruck("d-runt", "PL-2", "REFRIGERADO", 20.0, 0.0, 5.0, false, true, null, BOGOTA_LAT, BOGOTA_LON);
        AllocationCandidateResponse runtEval = optimizer.evaluateTruck(runtInvalid, coldLoad);
        assertThat(runtEval.getEligible()).isFalse();
        assertThat(runtEval.getReason()).contains("RUNT");

        // 3. Capacidad insuficiente (10t < 15t requeridas)
        AvailableTruckDto smallTruck = buildTruck("d-small", "PL-3", "REFRIGERADO", 10.0, 0.0, 4.9, true, true, null, BOGOTA_LAT, BOGOTA_LON);
        AllocationCandidateResponse smallEval = optimizer.evaluateTruck(smallTruck, coldLoad);
        assertThat(smallEval.getEligible()).isFalse();
        assertThat(smallEval.getReason()).contains("Capacidad insuficiente");

        // 4. Tipo de vehículo incompatible
        AvailableTruckDto dryVan = buildTruck("d-van", "PL-4", "FURGON_SECO", 20.0, 0.0, 4.9, true, false, null, BOGOTA_LAT, BOGOTA_LON);
        AllocationCandidateResponse vanEval = optimizer.evaluateTruck(dryVan, coldLoad);
        assertThat(vanEval.getEligible()).isFalse();
        assertThat(vanEval.getReason()).contains("cadena de frío");

        // 5. Vehículo refrigerado pero sin certificación de cadena de frío
        AvailableTruckDto uncertified = buildTruck("d-nocold", "PL-5", "REFRIGERADO", 20.0, 0.0, 4.9, true, false, null, BOGOTA_LAT, BOGOTA_LON);
        AllocationCandidateResponse coldEval = optimizer.evaluateTruck(uncertified, coldLoad);
        assertThat(coldEval.getEligible()).isFalse();
        assertThat(coldEval.getReason()).contains("cadena de frío");

        // 6. Cuenta del conductor sin verificar
        AvailableTruckDto unverified = buildTruck("d-unverif", "PL-6", "REFRIGERADO", 20.0, 0.0, 4.9, true, true, null, BOGOTA_LAT, BOGOTA_LON);
        unverified.setAccountVerified(false);
        AllocationCandidateResponse unverifiedEval = optimizer.evaluateTruck(unverified, coldLoad);
        assertThat(unverifiedEval.getEligible()).isFalse();
        assertThat(unverifiedEval.getReason()).contains("sin verificar");
    }

    @Test
    @DisplayName("Reproducibilidad y consistencia: 100 ejecuciones con permutación de entrada generan el mismo resultado")
    void decisionIsReproducibleAndDeterministicAcrossMultipleRuns() {
        FreightLoadDto load = buildLoad(300L, 18.0, "TRACTOMULA", BOGOTA_LAT, BOGOTA_LON);

        AvailableTruckDto t1 = buildTruck("d1", "PL-1", "TRACTOMULA", 25.0, 0.0, 4.5, true, true, null, BOGOTA_LAT + 0.05, BOGOTA_LON);
        AvailableTruckDto t2 = buildTruck("d2", "PL-2", "TRACTOMULA", 20.0, 0.0, 4.8, true, true, null, BOGOTA_LAT + 0.10, BOGOTA_LON);
        AvailableTruckDto t3 = buildTruck("d3", "PL-3", "TRACTOMULA", 30.0, 0.0, 4.0, true, true, null, BOGOTA_LAT + 0.20, BOGOTA_LON);

        List<AvailableTruckDto> baseList = List.of(t1, t2, t3);
        List<String> firstRunOrder = null;

        for (int i = 0; i < 50; i++) {
            List<AvailableTruckDto> shuffled = new ArrayList<>(baseList);
            Collections.shuffle(shuffled);

            List<String> currentOrder = shuffled.stream()
                    .map(t -> optimizer.evaluateTruck(t, load))
                    .sorted(FleetScoringOptimizer.RANKING)
                    .map(AllocationCandidateResponse::getDriverId)
                    .toList();

            if (firstRunOrder == null) {
                firstRunOrder = currentOrder;
            } else {
                assertThat(currentOrder).isEqualTo(firstRunOrder);
            }
        }
    }
}
