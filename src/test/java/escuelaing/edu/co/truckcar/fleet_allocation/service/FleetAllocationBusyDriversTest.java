package escuelaing.edu.co.truckcar.fleet_allocation.service;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.DriverCommitmentDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FleetDriverStateDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationCandidateResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationResultResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationDecision;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationStatus;
import escuelaing.edu.co.truckcar.fleet_allocation.repository.AllocationDecisionRepository;
import escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm.FleetScoringOptimizer;
import escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm.HaversineDistanceCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exclusión de conductores ocupados con el optimizador real (sin mocks de puntaje). */
class FleetAllocationBusyDriversTest {

    private RestClient restClient;
    private FleetAllocationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AllocationDecisionRepository repository = mock(AllocationDecisionRepository.class);
        restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        service = new FleetAllocationService(repository,
                new FleetScoringOptimizer(new HaversineDistanceCalculator()), restClient, mock(KafkaTemplate.class), new NeighborServiceCalls());

        when(restClient.get().uri(anyString()).retrieve().body(FreightLoadDto.class))
                .thenReturn(FreightLoadDto.builder().id(42L).weightTons(30.0).requiredTruckType("TRACTOMULA_3S3")
                        .originLatitude(4.61).originLongitude(-74.08).build());
        when(repository.save(any(AllocationDecision.class))).thenAnswer(invocation -> {
            AllocationDecision decision = invocation.getArgument(0);
            decision.setId(7L);
            return decision;
        });
        // PATCH /assign explícito: los deep stubs no resuelven el tipo genérico de headers().
        RestClient.RequestBodyUriSpec patchSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.ResponseSpec patchResponse = mock(RestClient.ResponseSpec.class);
        when(restClient.patch()).thenReturn(patchSpec);
        when(patchSpec.uri(anyString())).thenReturn(patchSpec);
        when(patchSpec.headers(any())).thenReturn(patchSpec);
        when(patchSpec.body(any(Object.class))).thenReturn(patchSpec);
        when(patchSpec.retrieve()).thenReturn(patchResponse);
        when(patchResponse.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
    }

    @SuppressWarnings("unchecked")
    private void fleet(FleetDriverStateDto... states) {
        when(restClient.get().uri(anyString()).retrieve().body(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(states));
    }

    private void commitments(DriverCommitmentDto... commitments) {
        when(restClient.get().uri(anyString()).retrieve().body(DriverCommitmentDto[].class)).thenReturn(commitments);
    }

    /** Todos idénticos salvo la distancia: el más cercano gana si está libre. */
    private FleetDriverStateDto driver(String id, double latitude) {
        return FleetDriverStateDto.builder()
                .driverId(id).driverName(id).vehiclePlate("PL-" + id).vehicleType("TRACTOMULA_3S3")
                .maxPayloadTons(34.0).netCargoWeightTons(0.0).rating(4.5).status("ACTIVO")
                .currentLatitude(latitude).currentLongitude(-74.08)
                .build();
    }

    private AllocationCandidateResponse candidate(AllocationResultResponse result, String driverId) {
        return result.getEvaluatedCandidates().stream()
                .filter(c -> driverId.equals(c.getDriverId())).findFirst().orElseThrow();
    }

    @Test
    void driversWithAcceptedInTransitOrPendingLoadsAreNotEligible() {
        fleet(driver("asignado", 4.61), driver("en-ruta", 4.62), driver("propuesto", 4.63), driver("libre", 5.5));
        commitments(
                DriverCommitmentDto.builder().driverId("asignado").loadId(11L).loadStatus("ASSIGNED").assignmentStatus("ACCEPTED").build(),
                DriverCommitmentDto.builder().driverId("en-ruta").loadId(12L).loadStatus("IN_TRANSIT").assignmentStatus("ACCEPTED").build(),
                DriverCommitmentDto.builder().driverId("propuesto").loadId(13L).loadStatus("AVAILABLE").assignmentStatus("NOTIFIED").build());

        AllocationResultResponse result = service.processAutomaticAllocationForLoad(42L);

        assertThat(result.getStatus()).isEqualTo(AllocationStatus.SUCCESSFUL);
        assertThat(result.getSelectedDriverId()).isEqualTo("libre");
        assertThat(candidate(result, "asignado").getEligible()).isFalse();
        assertThat(candidate(result, "asignado").getReason()).isEqualTo("Conductor ocupado con la carga #11 (ASSIGNED)");
        assertThat(candidate(result, "en-ruta").getReason()).contains("#12 (IN_TRANSIT)");
        assertThat(candidate(result, "propuesto").getReason()).contains("#13 (propuesta NOTIFIED pendiente)");
    }

    @Test
    void onTripFlagFromFleetsDriversIsHonoredEvenIfLoadAllocationIsDown() {
        FleetDriverStateDto onTrip = driver("en-ruta", 4.61);
        onTrip.setOnTrip(true);
        onTrip.setCurrentLoadId(12L);
        onTrip.setCurrentLoadStatus("IN_TRANSIT");
        fleet(onTrip, driver("libre", 5.5));
        when(restClient.get().uri(anyString()).retrieve().body(DriverCommitmentDto[].class))
                .thenThrow(new ResourceAccessException("Connection refused"));

        AllocationResultResponse result = service.processAutomaticAllocationForLoad(42L);

        assertThat(result.getSelectedDriverId()).isEqualTo("libre");
        assertThat(candidate(result, "en-ruta").getReason()).contains("#12 (IN_TRANSIT)");
    }

    @Test
    void theLoadBeingMatchedDoesNotBlockItsOwnDriver() {
        fleet(driver("ana", 4.61));
        commitments(DriverCommitmentDto.builder().driverId("ana").loadId(42L).loadStatus("AVAILABLE")
                .assignmentStatus("PROPOSED").build());

        AllocationResultResponse result = service.processAutomaticAllocationForLoad(42L);

        assertThat(result.getSelectedDriverId()).isEqualTo("ana");
    }

    @Test
    void reallocationAlsoSkipsBusyDriversAndReportsNoCandidates() {
        fleet(driver("ocupado", 4.61), driver("rechazo", 4.62));
        commitments(DriverCommitmentDto.builder().driverId("ocupado").loadId(11L).loadStatus("IN_TRANSIT")
                .assignmentStatus("ACCEPTED").build());

        AllocationResultResponse result = service.processAutomaticReallocationForLoad(
                42L, Set.of("rechazo"), "assignment.rejected", null);

        assertThat(result.getStatus()).isEqualTo(AllocationStatus.NO_DRIVERS_AVAILABLE);
        assertThat(candidate(result, "ocupado").getEligible()).isFalse();
    }
}
