package escuelaing.edu.co.truckcar.fleet_allocation.service;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.DriverCommitmentDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FleetDriverStateDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationResultResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationDecision;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationStatus;
import escuelaing.edu.co.truckcar.fleet_allocation.repository.AllocationDecisionRepository;
import escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm.FleetScoringOptimizer;
import escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm.HaversineDistanceCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reintento del matching de cargas en espera cuando un conductor se libera. */
class FleetAllocationRetryTest {

    private AllocationDecisionRepository repository;
    private RestClient restClient;
    private RestClient.RequestBodyUriSpec patchSpec;
    private FleetAllocationService service;

    @BeforeEach
    void setUp() {
        repository = mock(AllocationDecisionRepository.class);
        restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        service = new FleetAllocationService(repository,
                new FleetScoringOptimizer(new HaversineDistanceCalculator()), restClient, mock(KafkaTemplate.class), new NeighborServiceCalls());
        when(repository.save(any(AllocationDecision.class))).thenAnswer(invocation -> {
            AllocationDecision decision = invocation.getArgument(0);
            decision.setId(9L);
            return decision;
        });
        patchSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.ResponseSpec patchResponse = mock(RestClient.ResponseSpec.class);
        when(restClient.patch()).thenReturn(patchSpec);
        when(patchSpec.uri(anyString())).thenReturn(patchSpec);
        when(patchSpec.headers(any())).thenReturn(patchSpec);
        when(patchSpec.body(any(Object.class))).thenReturn(patchSpec);
        when(patchSpec.retrieve()).thenReturn(patchResponse);
        when(patchResponse.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
        // Cada carga releída por el matching real es la 42 (tractomula, 30 t).
        when(restClient.get().uri(anyString()).retrieve().body(FreightLoadDto.class)).thenReturn(load(42L, null, "TRACTOMULA_3S3"));
    }

    private static FreightLoadDto load(Long id, String assignmentStatus, String truckType) {
        return FreightLoadDto.builder().id(id).status("AVAILABLE").assignmentStatus(assignmentStatus)
                .weightTons(30.0).requiredTruckType(truckType).originLatitude(4.61).originLongitude(-74.08).build();
    }

    private void waiting(FreightLoadDto... loads) {
        when(restClient.get().uri(anyString()).retrieve().body(FreightLoadDto[].class)).thenReturn(loads);
    }

    @SuppressWarnings("unchecked")
    private void fleet(FleetDriverStateDto... states) {
        when(restClient.get().uri(anyString()).retrieve().body(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(states));
    }

    private void commitments(DriverCommitmentDto... commitments) {
        when(restClient.get().uri(anyString()).retrieve().body(DriverCommitmentDto[].class)).thenReturn(commitments);
    }

    private static FleetDriverStateDto truck(String id) {
        return FleetDriverStateDto.builder().accountVerified(true).driverId(id).driverName(id).vehiclePlate("PL-" + id)
                .vehicleType("TRACTOMULA_3S3").maxPayloadTons(34.0).netCargoWeightTons(0.0).rating(4.5).status("ACTIVO")
                .currentLatitude(4.6).currentLongitude(-74.08).build();
    }

    @Test
    void releasedDriverIsOfferedTheOldestWaitingLoadsInOrder() {
        waiting(load(44L, "UNASSIGNED_NO_DRIVERS", "TRACTOMULA_3S3"), load(42L, null, "TRACTOMULA_3S3"),
                load(43L, "NOTIFIED", "TRACTOMULA_3S3"));
        fleet(truck("ana"));
        commitments();

        List<AllocationResultResponse> results = service.retryWaitingLoads("trip.delivered");

        assertThat(results).extracting(AllocationResultResponse::getStatus).containsOnly(AllocationStatus.SUCCESSFUL);
        InOrder order = inOrder(patchSpec);
        order.verify(patchSpec).uri(endsWith("/loads/42/assign"));
        order.verify(patchSpec).uri(endsWith("/loads/44/assign"));
        verify(patchSpec, never()).uri(endsWith("/loads/43/assign"));
    }

    @Test
    void loadsWithoutAnEligibleTruckAreSkippedWithoutPersistingDecisions() {
        waiting(load(50L, null, "CAMION_SENCILLO"));
        fleet(truck("ana"));
        commitments();

        assertThat(service.retryWaitingLoads("load.cancelled")).isEmpty();
        verify(repository, never()).save(any());
        verify(restClient, never()).patch();
    }

    @Test
    void stopsWhenEveryDriverIsBusy() {
        waiting(load(42L, null, "TRACTOMULA_3S3"), load(44L, null, "TRACTOMULA_3S3"));
        fleet(truck("ana"));
        commitments(DriverCommitmentDto.builder().driverId("ana").loadId(7L).loadStatus("IN_TRANSIT").build());

        assertThat(service.retryWaitingLoads("trip.delivered")).isEmpty();
        verify(restClient, never()).patch();
    }

    @Test
    void staleOnTripFromFleetsDriversDoesNotBlockADriverThatLoadAllocationReportsFree() {
        // fleets-drivers aún no procesó el trip.delivered; Load-Allocation ya no lo cuenta como ocupado.
        FleetDriverStateDto stale = truck("ana");
        stale.setOnTrip(true);
        stale.setCurrentLoadId(7L);
        stale.setCurrentLoadStatus("IN_TRANSIT");
        waiting(load(42L, null, "TRACTOMULA_3S3"));
        fleet(stale);
        commitments();

        List<AllocationResultResponse> results = service.retryWaitingLoads("trip.delivered");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSelectedDriverId()).isEqualTo("ana");
    }
}
