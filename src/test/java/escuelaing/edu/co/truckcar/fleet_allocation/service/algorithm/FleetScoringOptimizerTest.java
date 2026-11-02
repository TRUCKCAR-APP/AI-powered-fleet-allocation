package escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.AvailableTruckDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FleetDriverStateDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationCandidateResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Elegibilidad y puntaje del motor: capacidad real, RUNT, cadena de frío y cercanía sin empates artificiales. */
class FleetScoringOptimizerTest {

    private final FleetScoringOptimizer optimizer = new FleetScoringOptimizer(new HaversineDistanceCalculator());

    private static final FreightLoadDto LOAD = FreightLoadDto.builder().id(88L).weightTons(24.0)
            .requiredTruckType("TRACTOMULA_3S3").originLatitude(4.6486).originLongitude(-74.1481).build();

    private static FleetDriverStateDto.FleetDriverStateDtoBuilder state(String id) {
        return FleetDriverStateDto.builder().driverId(id).driverName(id).vehiclePlate("PL-" + id).vehicleType("TRACTOMULA_3S3")
                .rating(4.5).status("ACTIVO").currentLatitude(4.6486).currentLongitude(-74.1481);
    }

    @Test
    void anEmptyTruckWithEnoughPayloadIsEligible() {
        // El caso que el motor descartaba siempre: vacío (0 t a bordo) con 34 t de capacidad.
        AvailableTruckDto empty = state("ana").maxPayloadTons(34.0).netCargoWeightTons(0.0).build().toAvailableTruck();
        AllocationCandidateResponse result = optimizer.evaluateTruck(empty, LOAD);

        assertThat(empty.getCapacityTons()).isEqualTo(34.0);
        assertThat(result.getEligible()).isTrue();
        assertThat(result.getCapacityUtilizationPercent()).isEqualTo(70.6);
    }

    @Test
    void cargoAlreadyOnBoardReducesTheAvailablePayload() {
        AvailableTruckDto loaded = state("carlos").maxPayloadTons(34.0).netCargoWeightTons(20.0).build().toAvailableTruck();
        AllocationCandidateResponse result = optimizer.evaluateTruck(loaded, LOAD);

        assertThat(loaded.getCapacityTons()).isEqualTo(14.0);
        assertThat(result.getEligible()).isFalse();
        assertThat(result.getReason()).contains("Capacidad insuficiente");
    }

    @Test
    void withoutARegisteredPayloadTheTruckIsNotProposed() {
        AllocationCandidateResponse result = optimizer.evaluateTruck(state("diana").netCargoWeightTons(0.0).build().toAvailableTruck(), LOAD);
        assertThat(result.getEligible()).isFalse();
        assertThat(result.getReason()).isEqualTo("Capacidad de carga del vehículo sin registrar");
    }

    @Test
    void anExpiredRuntOrMissingColdChainCertificationExcludesTheTruck() {
        AllocationCandidateResponse runt = optimizer.evaluateTruck(
                state("a").maxPayloadTons(34.0).runtValid(false).build().toAvailableTruck(), LOAD);
        assertThat(runt.getEligible()).isFalse();
        assertThat(runt.getReason()).contains("RUNT");

        FreightLoadDto cold = FreightLoadDto.builder().id(9L).weightTons(10.0).requiredTruckType("REFRIGERADO")
                .originLatitude(4.6486).originLongitude(-74.1481).build();
        AvailableTruckDto reefer = state("b").vehicleType("REFRIGERADO").maxPayloadTons(20.0).coldChainCertified(false).build().toAvailableTruck();
        assertThat(optimizer.evaluateTruck(reefer, cold).getReason()).contains("cadena de frío");

        reefer.setColdChainCertified(true);
        assertThat(optimizer.evaluateTruck(reefer, cold).getEligible()).isTrue();
    }

    @Test
    void distantTrucksAreStillRankedByDistance() {
        // A 310 km y a 900 km del origen: con el corte lineal en 300 km los dos valían 0 en cercanía.
        AllocationCandidateResponse near = optimizer.evaluateTruck(
                state("cerca").maxPayloadTons(34.0).currentLatitude(4.6486 + 2.79).build().toAvailableTruck(), LOAD);
        AllocationCandidateResponse far = optimizer.evaluateTruck(
                state("lejos").maxPayloadTons(34.0).currentLatitude(4.6486 + 8.09).build().toAvailableTruck(), LOAD);

        assertThat(near.getScore()).isGreaterThan(far.getScore());
        assertThat(List.of(far, near).stream().sorted(FleetScoringOptimizer.RANKING).map(AllocationCandidateResponse::getDriverId))
                .containsExactly("cerca", "lejos");
    }

    @Test
    void equalScoresAreOrderedByDistanceThenByDriverId() {
        AllocationCandidateResponse b = AllocationCandidateResponse.builder().driverId("b").score(80.0).deadheadDistanceKm(5.0).build();
        AllocationCandidateResponse a = AllocationCandidateResponse.builder().driverId("a").score(80.0).deadheadDistanceKm(5.0).build();
        AllocationCandidateResponse closer = AllocationCandidateResponse.builder().driverId("z").score(80.0).deadheadDistanceKm(1.0).build();
        AllocationCandidateResponse best = AllocationCandidateResponse.builder().driverId("y").score(90.0).deadheadDistanceKm(50.0).build();

        assertThat(List.of(b, a, closer, best).stream().sorted(FleetScoringOptimizer.RANKING).map(AllocationCandidateResponse::getDriverId))
                .containsExactly("y", "z", "a", "b");
    }
}
