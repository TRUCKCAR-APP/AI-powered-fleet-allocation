package escuelaing.edu.co.truckcar.fleet_allocation.service;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.AvailableTruckDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.request.ManualAllocationRequest;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationCandidateResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationResultResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationDecision;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationStatus;
import escuelaing.edu.co.truckcar.fleet_allocation.repository.AllocationDecisionRepository;
import escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm.FleetScoringOptimizer;
import escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm.HaversineDistanceCalculator;
import escuelaing.edu.co.truckcar.fleet_allocation.service.route.RouteRestrictionScreen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El motor no propone una carga a un camión que ninguna ruta admite por gálibo o peso: queda no elegible con el motivo
 * del filtro y la carga va al siguiente candidato, o queda sin conductores si nadie la puede llevar.
 */
class FleetAllocationRouteFilterTest {

    private static final String REASON = "Incompatible por gálibo/peso en la ruta (Límite: 3,80 m, Vehículo: 4,10 m)";
    private static final FreightLoadDto LOAD = FreightLoadDto.builder().id(88L).weightTons(25.0)
            .requiredTruckType("TRACTOMULA_3S3").originLatitude(4.6097).originLongitude(-74.0817)
            .destinationLatitude(5.5353).destinationLongitude(-73.3678).build();

    private AllocationDecisionRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(AllocationDecisionRepository.class);
        when(repository.save(any(AllocationDecision.class))).thenAnswer(invocation -> {
            AllocationDecision decision = invocation.getArgument(0);
            decision.setId(1L);
            return decision;
        });
    }

    private FleetAllocationService serviceWith(Map<String, String> incompatible) {
        RouteRestrictionScreen screen = (load, trucks) -> trucks.forEach(truck -> {
            assertThat(load.getDestinationLatitude()).isEqualTo(5.5353);
            truck.setRouteRestrictionReason(incompatible.get(truck.getDriverId()));
        });
        return new FleetAllocationService(repository, new FleetScoringOptimizer(new HaversineDistanceCalculator()),
                mock(RestClient.class), mock(KafkaTemplate.class), new NeighborServiceCalls(), screen);
    }

    /** "cerca" está junto al origen y ganaría por cercanía; "lejos" está a ~100 km. */
    private static List<AvailableTruckDto> fleet() {
        return List.of(
                AvailableTruckDto.builder().driverId("cerca").driverName("Cerca").plate("ALT-410").truckType("TRACTOMULA_3S3")
                        .capacityTons(34.0).rating(4.8).accountVerified(true).heightMeters(4.1).tareWeightTons(15.0)
                        .currentLatitude(4.61).currentLongitude(-74.08).build(),
                AvailableTruckDto.builder().driverId("lejos").driverName("Lejos").plate("BAJ-350").truckType("TRACTOMULA_3S3")
                        .capacityTons(34.0).rating(4.5).accountVerified(true).heightMeters(3.5).tareWeightTons(14.0)
                        .currentLatitude(5.4).currentLongitude(-73.5).build());
    }

    @Test
    void theClosestTruckIsSkippedWhenNoRouteFitsItAndTheLoadGoesToTheNextOne() {
        AllocationResultResponse result = serviceWith(Map.of("cerca", REASON))
                .processManualOptimization(ManualAllocationRequest.builder().load(LOAD).candidateTrucks(fleet()).build());

        assertThat(result.getStatus()).isEqualTo(AllocationStatus.SUCCESSFUL);
        assertThat(result.getSelectedDriverId()).isEqualTo("lejos");
        AllocationCandidateResponse rejected = result.getEvaluatedCandidates().stream()
                .filter(c -> "cerca".equals(c.getDriverId())).findFirst().orElseThrow();
        assertThat(rejected.getEligible()).isFalse();
        assertThat(rejected.getScore()).isZero();
        assertThat(rejected.getReason()).isEqualTo(REASON);
        assertThat(result.getExplanation())
                .startsWith("Asignado por algoritmo determinista (Cercanía + Capacidad + Calificación): BAJ-350 (Lejos)")
                .contains("Candidato ALT-410 (Cerca) descartado por gálibo/peso en la ruta (Límite: 3,80 m, Vehículo: 4,10 m).");
    }

    @Test
    void withoutTheRouteFilterTheClosestTruckWouldWin() {
        AllocationResultResponse result = serviceWith(Map.of())
                .processManualOptimization(ManualAllocationRequest.builder().load(LOAD).candidateTrucks(fleet()).build());

        assertThat(result.getSelectedDriverId()).isEqualTo("cerca");
    }

    @Test
    void ifNoTruckFitsTheRouteTheLoadIsNotAssigned() {
        AllocationResultResponse result = serviceWith(Map.of("cerca", REASON, "lejos", REASON))
                .processManualOptimization(ManualAllocationRequest.builder().load(LOAD).candidateTrucks(fleet()).build());

        assertThat(result.getStatus()).isNotEqualTo(AllocationStatus.SUCCESSFUL);
        assertThat(result.getSelectedDriverId()).isNull();
        assertThat(result.getEvaluatedCandidates()).allSatisfy(c -> assertThat(c.getReason()).isEqualTo(REASON));
        assertThat(result.getExplanation()).startsWith("Sin asignación: ningún candidato elegible entre 2 evaluados.")
                .contains("Candidato ALT-410 (Cerca) descartado por gálibo", "Candidato BAJ-350 (Lejos) descartado por gálibo");
    }
}
