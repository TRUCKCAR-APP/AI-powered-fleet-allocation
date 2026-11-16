package escuelaing.edu.co.truckcar.fleet_allocation.service;

import escuelaing.edu.co.truckcar.fleet_allocation.service.route.RouteRestrictionScreen;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.AvailableTruckDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.event.EventEnvelope;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FleetDriverStateDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationCandidateResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationResultResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationDecision;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationStatus;
import escuelaing.edu.co.truckcar.fleet_allocation.repository.AllocationDecisionRepository;
import escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm.FleetScoringOptimizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FleetAllocationMatchTest {

    private AllocationDecisionRepository repository;
    private FleetScoringOptimizer optimizer;
    private RestClient restClient;
    private FleetAllocationService service;
    private AllocationDecision savedDecision;
    private RestClient.ResponseSpec patchResponse;
    private KafkaTemplate<String, Object> kafka;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(AllocationDecisionRepository.class);
        optimizer = mock(FleetScoringOptimizer.class);
        restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        kafka = mock(KafkaTemplate.class);
        service = new FleetAllocationService(repository, optimizer, restClient, kafka, new NeighborServiceCalls(),
                RouteRestrictionScreen.disabled());

        when(restClient.get().uri(anyString()).retrieve().body(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(state("driver-old"), state("driver-new")));
        when(optimizer.evaluateTruck(any(AvailableTruckDto.class), any(FreightLoadDto.class)))
                .thenAnswer(invocation -> {
                    AvailableTruckDto truck = invocation.getArgument(0);
                    return AllocationCandidateResponse.builder()
                            .driverId(truck.getDriverId())
                            .driverName(truck.getDriverName())
                            .vehiclePlate(truck.getPlate())
                            .score("driver-old".equals(truck.getDriverId()) ? 99.0 : 70.0)
                            .eligible(true)
                            .build();
                });
        when(repository.save(any(AllocationDecision.class))).thenAnswer(invocation -> {
            savedDecision = invocation.getArgument(0);
            savedDecision.setId(10L);
            return savedDecision;
        });
        when(repository.findById(10L)).thenAnswer(invocation -> Optional.ofNullable(savedDecision));
    }

    @Test
    void reopenedLoadExcludesDiscardedDriversAndConfirmsTheNewCandidate() {
        givenLoad(List.of("driver-old"));

        AllocationResultResponse result = service.processAutomaticAllocationForLoad(42L);

        assertThat(result.getStatus()).isEqualTo(AllocationStatus.SUCCESSFUL);
        assertThat(result.getSelectedDriverId()).isEqualTo("driver-new");
    }

    @Test
    void conflictFromLoadAllocationIsNotReportedAsSuccessful() {
        givenLoad(List.of());
        when(patchResponse.toBodilessEntity())
                .thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY,
                        "{\"message\":\"La carga #42 no admite una nueva asignación (status=AVAILABLE, assignmentStatus=NOTIFIED)\"}"
                                .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        AllocationResultResponse result = service.processAutomaticAllocationForLoad(42L);

        assertThat(result.getStatus()).isEqualTo(AllocationStatus.ASSIGNMENT_CONFLICT);
        assertThat(result.getReasonDetails()).contains("assignmentStatus=NOTIFIED");
        ArgumentCaptor<AllocationDecision> captor = ArgumentCaptor.forClass(AllocationDecision.class);
        verify(repository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AllocationStatus.REJECTED);
    }

    @Test
    void unreachableLoadAllocationIsReportedAsAssignmentFailed() {
        givenLoad(List.of());
        when(patchResponse.toBodilessEntity())
                .thenThrow(new ResourceAccessException("Connection refused"));

        AllocationResultResponse result = service.processAutomaticAllocationForLoad(42L);

        assertThat(result.getStatus()).isEqualTo(AllocationStatus.ASSIGNMENT_FAILED);
        assertThat(savedDecision.getStatus()).isEqualTo(AllocationStatus.RECOMMENDATION_PENDING);
    }

    /**
     * Bug HU2.1: sin candidatos en la primera asignación solo se publicaba FleetAllocationCompletedEvent (sin sobre),
     * que Load-Allocation ignora, y la carga quedaba AVAILABLE sin estado de asignación para siempre.
     */
    @Test
    void firstAllocationWithoutCandidatesAnnouncesNoCandidatesToLoadAllocation() {
        givenLoad(List.of());
        when(optimizer.evaluateTruck(any(AvailableTruckDto.class), any(FreightLoadDto.class)))
                .thenAnswer(invocation -> AllocationCandidateResponse.builder()
                        .driverId(((AvailableTruckDto) invocation.getArgument(0)).getDriverId())
                        .eligible(false).score(0.0).build());

        AllocationResultResponse result = service.processAutomaticAllocationForLoad(42L);

        assertThat(result.getStatus()).isEqualTo(AllocationStatus.NO_DRIVERS_AVAILABLE);
        assertThat(publishedEventTypes()).contains("agent.no.candidates");
        verify(restClient, never()).patch();
    }

    @Test
    void aConflictLeavesTheLoadWaitingAndAnnouncesIt() {
        givenLoad(List.of());
        when(patchResponse.toBodilessEntity())
                .thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY,
                        "{\"message\":\"El conductor driver-old está ocupado\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        service.processAutomaticAllocationForLoad(42L);

        assertThat(publishedEventTypes()).contains("agent.no.candidates");
    }

    @Test
    void aConfirmedProposalDoesNotAnnounceNoCandidates() {
        givenLoad(List.of());

        service.processAutomaticAllocationForLoad(42L);

        assertThat(publishedEventTypes()).doesNotContain("agent.no.candidates");
    }

    private List<String> publishedEventTypes() {
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafka, org.mockito.Mockito.atLeast(0)).send(any(), anyString(), sent.capture());
        return sent.getAllValues().stream()
                .filter(EventEnvelope.class::isInstance)
                .map(event -> ((EventEnvelope<?>) event).getEventType())
                .toList();
    }

    private void givenLoad(List<String> discardedDriverIds) {
        when(restClient.get().uri(anyString()).retrieve().body(FreightLoadDto.class))
                .thenReturn(FreightLoadDto.builder().id(42L).weightTons(20.0)
                        .discardedDriverIds(discardedDriverIds).build());
        // Cadena PATCH explícita: los deep stubs no resuelven el tipo genérico que devuelve headers().
        RestClient.RequestBodyUriSpec patchSpec = mock(RestClient.RequestBodyUriSpec.class);
        patchResponse = mock(RestClient.ResponseSpec.class);
        when(restClient.patch()).thenReturn(patchSpec);
        when(patchSpec.uri(anyString())).thenReturn(patchSpec);
        when(patchSpec.headers(any())).thenReturn(patchSpec);
        when(patchSpec.body(any(Object.class))).thenReturn(patchSpec);
        when(patchSpec.retrieve()).thenReturn(patchResponse);
        when(patchResponse.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
    }

    private FleetDriverStateDto state(String driverId) {
        return FleetDriverStateDto.builder().accountVerified(true)
                .driverId(driverId)
                .driverName(driverId)
                .vehiclePlate("PL-" + driverId)
                .vehicleType("TRACTOMULA_3S3")
                .maxPayloadTons(34.0).netCargoWeightTons(0.0)
                .status("ACTIVO")
                .build();
    }
}
