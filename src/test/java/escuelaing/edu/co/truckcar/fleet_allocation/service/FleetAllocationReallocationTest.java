package escuelaing.edu.co.truckcar.fleet_allocation.service;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.AvailableTruckDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FleetDriverStateDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.event.EventEnvelope;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationCandidateResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationResultResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationDecision;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationStatus;
import escuelaing.edu.co.truckcar.fleet_allocation.repository.AllocationDecisionRepository;
import escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm.FleetScoringOptimizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FleetAllocationReallocationTest {

    private static final UUID CORRELATION_ID = UUID.fromString("7f0c2a52-9d1e-4c11-8a4b-2f6c3d9e1a01");

    private AllocationDecisionRepository repository;
    private FleetScoringOptimizer optimizer;
    private RestClient restClient;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private FleetAllocationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(AllocationDecisionRepository.class);
        optimizer = mock(FleetScoringOptimizer.class);
        restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        kafkaTemplate = mock(KafkaTemplate.class);
        service = new FleetAllocationService(repository, optimizer, restClient, kafkaTemplate, new NeighborServiceCalls());

        when(restClient.get().uri(anyString()).retrieve().body(FreightLoadDto.class))
                .thenReturn(FreightLoadDto.builder().id(42L).weightTons(20.0).build());
        when(restClient.get().uri(anyString()).retrieve().body(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(state("driver-1", "ACTIVO", false), state("driver-2", "ACTIVO", false),
                        state("driver-3", "ACTIVO", false), state("driver-4", "INACTIVO", false),
                        state("driver-5", "ACTIVO", true)));
        when(optimizer.evaluateTruck(any(AvailableTruckDto.class), any(FreightLoadDto.class)))
                .thenAnswer(invocation -> {
                    AvailableTruckDto truck = invocation.getArgument(0);
                    return AllocationCandidateResponse.builder()
                            .driverId(truck.getDriverId())
                            .vehiclePlate(truck.getPlate())
                            .score("driver-1".equals(truck.getDriverId()) ? 99.0 : 50.0)
                            .eligible(true)
                            .build();
                });
        when(repository.save(any(AllocationDecision.class))).thenAnswer(invocation -> {
            AllocationDecision decision = invocation.getArgument(0);
            decision.setId(1L);
            return decision;
        });
    }

    @Test
    void excludesEveryDiscardedDriverAndPublishesScoreReady() {
        AllocationResultResponse result = service.processAutomaticReallocationForLoad(
                42L, Set.of("driver-1", "driver-2"), "assignment.rejected", CORRELATION_ID);

        assertThat(result.getSelectedDriverId()).isEqualTo("driver-3");
        EventEnvelope<?> envelope = publishedEnvelope();
        assertThat(envelope.getEventType()).isEqualTo("agent.score.ready");
        assertThat(envelope.getCorrelationId()).isEqualTo(CORRELATION_ID);
    }

    @Test
    void publishesNoCandidatesWhenAllDriversWereDiscarded() {
        AllocationResultResponse result = service.processAutomaticReallocationForLoad(
                42L, Set.of("driver-1", "driver-2", "driver-3"), "assignment.timeout", CORRELATION_ID);

        assertThat(result.getStatus()).isEqualTo(AllocationStatus.NO_DRIVERS_AVAILABLE);
        EventEnvelope<?> envelope = publishedEnvelope();
        assertThat(envelope.getEventType()).isEqualTo("agent.no.candidates");
        assertThat(envelope.getCorrelationId()).isEqualTo(CORRELATION_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unavailableFleetServiceFallsBackToNoCandidatesInsteadOfThrowing() {
        when(restClient.get().uri(anyString()).retrieve().body(any(ParameterizedTypeReference.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"));

        AllocationResultResponse result = service.processAutomaticAllocationForLoad(42L, CORRELATION_ID);

        // noCandidatesAvailable: la carga queda en espera (UNASSIGNED_NO_DRIVERS en Load-Allocation) sin bloquear Kafka.
        assertThat(result.getStatus()).isEqualTo(AllocationStatus.NO_DRIVERS_AVAILABLE);
        assertThat(result.getReasonDetails()).contains("Servicios vecinos no disponibles");
        EventEnvelope<?> envelope = publishedEnvelope();
        assertThat(envelope.getEventType()).isEqualTo("agent.no.candidates");
        assertThat(envelope.getCorrelationId()).isEqualTo(CORRELATION_ID);
    }

    @Test
    void missingLoadStaysPendingWithoutPublishing() {
        when(restClient.get().uri(anyString()).retrieve().body(FreightLoadDto.class))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Not Found",
                        org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        AllocationResultResponse result = service.processAutomaticAllocationForLoad(42L, CORRELATION_ID);

        assertThat(result.getStatus()).isEqualTo(AllocationStatus.RECOMMENDATION_PENDING);
        verify(kafkaTemplate, Mockito.never()).send(Mockito.<String>any(), any(), any());
    }

    private EventEnvelope<?> publishedEnvelope() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(Mockito.<String>any(), eq("42"), captor.capture());
        return (EventEnvelope<?>) captor.getValue();
    }

    @Test
    void onlyActiveDriversWithoutRestAlertAreEvaluated() {
        AllocationResultResponse result = service.processAutomaticReallocationForLoad(
                42L, Set.of("driver-1", "driver-2", "driver-3"), "assignment.rejected", CORRELATION_ID);

        // driver-4 está INACTIVO y driver-5 tiene alerta de descanso: ninguno es candidato.
        assertThat(result.getStatus()).isEqualTo(AllocationStatus.NO_DRIVERS_AVAILABLE);
        assertThat(result.getEvaluatedCandidates()).isEmpty();
    }

    private FleetDriverStateDto state(String driverId, String status, boolean restRequired) {
        return FleetDriverStateDto.builder()
                .driverId(driverId)
                .vehiclePlate("PL-" + driverId)
                .vehicleType("TRACTOMULA_3S3")
                .netCargoWeightTons(34.0)
                .status(status)
                .restRequiredAlert(restRequired)
                .build();
    }
}
