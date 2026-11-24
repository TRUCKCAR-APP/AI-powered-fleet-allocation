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
        return FleetDriverStateDto.builder().accountVerified(true).driverId(id).driverName(id).vehiclePlate("PL-" + id).vehicleType("TRACTOMULA_3S3")
                .rating(4.5).status("ACTIVO").currentLatitude(4.6486).currentLongitude(-74.1481);
    }

    /** Carga propia de una transportadora: solo sus conductores entran; los demás quedan fuera con el motivo. */
    @Test
    void anOwnLoadOfACompanyIsOnlyForItsDrivers() {
        FreightLoadDto own = FreightLoadDto.builder().id(90L).weightTons(24.0).requiredTruckType("TRACTOMULA_3S3")
                .originLatitude(4.6486).originLongitude(-74.1481).ownerCompanyId("c-andinos")
                .generatorCompanyName("Transportes Andinos").build();
        AvailableTruckDto mine = state("andres").companyId("c-andinos").maxPayloadTons(34.0).build().toAvailableTruck();
        AvailableTruckDto other = state("jorge").companyId("c-pacifico").maxPayloadTons(34.0).build().toAvailableTruck();
        AvailableTruckDto independent = state("libre").maxPayloadTons(34.0).build().toAvailableTruck();

        assertThat(optimizer.evaluateTruck(mine, own).getEligible()).isTrue();
        for (AvailableTruckDto truck : java.util.List.of(other, independent)) {
            AllocationCandidateResponse result = optimizer.evaluateTruck(truck, own);
            assertThat(result.getEligible()).isFalse();
            assertThat(result.getReason()).isEqualTo("Carga propia de Transportes Andinos: solo para sus conductores");
        }
        assertThat(optimizer.evaluateTruck(other, LOAD).getEligible()).as("una carga de un generador sigue abierta a todos").isTrue();
    }

    @Test
    void anUnverifiedAccountIsNeverEligibleAndTheReasonSaysWhy() {
        AvailableTruckDto pending = state("nuevo").accountVerified(false).maxPayloadTons(34.0).build().toAvailableTruck();
        AvailableTruckDto unknown = state("sin-dato").accountVerified(null).maxPayloadTons(34.0).build().toAvailableTruck();

        for (AvailableTruckDto truck : java.util.List.of(pending, unknown)) {
            AllocationCandidateResponse result = optimizer.evaluateTruck(truck, LOAD);
            assertThat(result.getEligible()).isFalse();
            assertThat(result.getScore()).isZero();
            assertThat(result.getReason()).contains("sin verificar");
        }
    }

    @Test
    void aTruckThatTheRouteRejectsIsNotEligibleAndKeepsTheReason() {
        AvailableTruckDto tall = state("alto").maxPayloadTons(34.0).build().toAvailableTruck();
        tall.setRouteRestrictionReason("Incompatible por gálibo/peso en la ruta (Límite: 3,80 m, Vehículo: 4,10 m)");

        AllocationCandidateResponse result = optimizer.evaluateTruck(tall, LOAD);

        assertThat(result.getEligible()).isFalse();
        assertThat(result.getScore()).isZero();
        assertThat(result.getReason()).isEqualTo("Incompatible por gálibo/peso en la ruta (Límite: 3,80 m, Vehículo: 4,10 m)");
    }

    @Test
    void theVehicleDataNeededForTheRouteCheckComesFromFleetsDrivers() {
        AvailableTruckDto truck = state("ana").maxPayloadTons(34.0).maxHeightMeters(4.1).tareWeightTons(15.0)
                .netCargoWeightTons(2.0).build().toAvailableTruck();

        assertThat(truck.getHeightMeters()).isEqualTo(4.1);
        assertThat(truck.getTareWeightTons()).isEqualTo(15.0);
        assertThat(truck.getOnBoardTons()).isEqualTo(2.0);
    }

    /** Lote 5: el puntaje se explica: cercanía (50 %), capacidad (30 %) y calificación (20 %) suman el total. */
    @Test
    void theScoreIsBrokenDownIntoItsThreeWeightedFactors() {
        AvailableTruckDto here = state("ana").maxPayloadTons(30.0).netCargoWeightTons(0.0).rating(4.5).build().toAvailableTruck();

        AllocationCandidateResponse r = optimizer.evaluateTruck(here, LOAD);

        assertThat(r.getProximityPoints()).as("en el mismo punto de recogida: los 50 puntos").isEqualTo(50.0);
        assertThat(r.getCapacityPoints()).as("24 de 30 t = 80 % de 30 puntos").isEqualTo(24.0);
        assertThat(r.getRatingPoints()).as("4,5 de 5 = 90 % de 20 puntos").isEqualTo(18.0);
        assertThat(r.getProximityPoints() + r.getCapacityPoints() + r.getRatingPoints())
                .isCloseTo(r.getScore(), org.assertj.core.data.Offset.offset(0.2));
    }

    @Test
    void anIneligibleTruckHasNoBreakdown() {
        AvailableTruckDto small = state("chico").maxPayloadTons(10.0).build().toAvailableTruck();

        AllocationCandidateResponse r = optimizer.evaluateTruck(small, LOAD);

        assertThat(r.getEligible()).isFalse();
        assertThat(r.getProximityPoints()).isNull();
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
