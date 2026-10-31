package escuelaing.edu.co.truckcar.fleet_allocation.service;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.AvailableTruckDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.DriverCommitmentDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FleetDriverStateDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.event.FleetAllocationCompletedEvent;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.event.AgentScoreReadyEvent;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.event.EventEnvelope;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.request.ManualAllocationRequest;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationCandidateResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationResultResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationDecision;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationStatus;
import escuelaing.edu.co.truckcar.fleet_allocation.repository.AllocationDecisionRepository;
import escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm.FleetScoringOptimizer;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FleetAllocationService implements IFleetAllocationService {

    private final AllocationDecisionRepository repository;
    private final FleetScoringOptimizer optimizer;
    private final RestClient restClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    /** CircuitBreaker + Retry de las llamadas a fleets-drivers y Load-Allocation. */
    private final NeighborServiceCalls neighborCalls;

    /** Estados de asignación de una carga AVAILABLE que espera conductor (además de null: nunca propuesta). */
    private static final Set<String> WAITING_ASSIGNMENT_STATUSES = Set.of("UNASSIGNED_NO_DRIVERS");

    @Value("${truckcar.allocation.retry-batch-size:20}")
    private int retryBatchSize = 20;

    @Value("${truckcar.services.load-allocation-url:http://localhost:8083}")
    private String loadAllocationServiceUrl;

    @Value("${truckcar.services.fleets-drivers-url:http://localhost:8081}")
    private String fleetsDriversServiceUrl;

    @Value("${truckcar.kafka.topic.allocation-results:truckcar.events.fleet-allocation-results}")
    private String allocationTopic;

    @Override
    @Transactional
    public AllocationResultResponse processManualOptimization(ManualAllocationRequest request) {
        FreightLoadDto load = request.getLoad();
        List<AvailableTruckDto> candidates = request.getCandidateTrucks();

        List<AllocationCandidateResponse> evaluatedList = candidates.stream()
                .map(truck -> optimizer.evaluateTruck(truck, load))
                .sorted(Comparator.comparing(AllocationCandidateResponse::getScore).reversed())
                .collect(Collectors.toList());

        AllocationCandidateResponse bestCandidate = evaluatedList.stream()
                .filter(AllocationCandidateResponse::getEligible)
                .findFirst()
                .orElse(null);

        AllocationDecision decision = buildDecisionEntity(load.getId(), bestCandidate, evaluatedList.size());
        AllocationDecision saved = repository.save(decision);

        publishAllocationKafkaEvent(saved);

        return AllocationResultResponse.builder()
                .decisionId(saved.getId())
                .loadId(saved.getLoadId())
                .status(saved.getStatus())
                .selectedDriverId(saved.getSelectedDriverId())
                .selectedDriverName(saved.getSelectedDriverName())
                .selectedVehiclePlate(saved.getSelectedVehiclePlate())
                .matchingScore(saved.getMatchingScore())
                .deadheadDistanceKm(saved.getDeadheadDistanceKm())
                .reasonDetails(saved.getReasonDetails())
                .evaluatedCandidates(evaluatedList)
                .executedAt(saved.getExecutedAt())
                .build();
    }

    /** Span y métrica truckcar_allocation_matching_seconds (duración del matching automático de una carga). */
    @Override
    @Transactional
    @Observed(name = "truckcar.allocation.matching", contextualName = "allocation matching",
            lowCardinalityKeyValues = {"trigger", "load.published"})
    public AllocationResultResponse processAutomaticAllocationForLoad(Long loadId, UUID correlationId) {
        countAiRequest();
        Optional<FreightLoadDto> load;
        Optional<List<AvailableTruckDto>> availableTrucks;
        try {
            load = fetchLoadFromService(loadId);
            if (load.isEmpty()) {
                return pendingRecommendation(loadId,
                        "La carga no existe en Load-Allocation; la recomendación queda pendiente sin datos sintéticos");
            }
            availableTrucks = fetchAvailableTrucksFromService(loadId);
        } catch (RuntimeException unavailable) {
            return noCandidatesAvailable(loadId, Set.of(), "load.published", correlationId, unavailable);
        }

        // Una carga reabierta (UNASSIGNED_NO_DRIVERS) no se vuelve a proponer a quien ya la rechazó o dejó vencer.
        Set<String> discarded = load.get().getDiscardedDriverIds() == null
                ? Set.of() : Set.copyOf(load.get().getDiscardedDriverIds());
        List<AvailableTruckDto> candidates = availableTrucks.get().stream()
                .filter(truck -> !discarded.contains(truck.getDriverId()))
                .collect(Collectors.toList());

        ManualAllocationRequest request = ManualAllocationRequest.builder()
                .load(load.get())
                .candidateTrucks(candidates)
                .build();

        AllocationResultResponse result = processManualOptimization(request);
        if (result.getStatus() != AllocationStatus.SUCCESSFUL) {
            return result;
        }

        AssignmentConfirmation confirmation = confirmAssignmentInLoadService(loadId, result.getSelectedDriverId(),
                result.getSelectedDriverName(), result.getSelectedVehiclePlate(), correlationId);
        if (confirmation.accepted()) {
            return result;
        }
        return markDecisionNotApplied(result, confirmation);
    }

    /**
     * La recomendación existe pero Load-Allocation no la aplicó: se corrige la decisión persistida y la respuesta
     * deja de reportar SUCCESSFUL.
     */
    private AllocationResultResponse markDecisionNotApplied(AllocationResultResponse result,
                                                            AssignmentConfirmation confirmation) {
        String reason = (confirmation.conflict()
                ? "Load-Allocation rechazó la asignación (409): "
                : "Load-Allocation no confirmó la asignación: ") + confirmation.detail();
        repository.findById(result.getDecisionId()).ifPresent(decision -> {
            decision.setStatus(confirmation.conflict() ? AllocationStatus.REJECTED : AllocationStatus.RECOMMENDATION_PENDING);
            decision.setReasonDetails(reason.length() > 250 ? reason.substring(0, 250) : reason);
            repository.save(decision);
        });
        result.setStatus(confirmation.conflict() ? AllocationStatus.ASSIGNMENT_CONFLICT : AllocationStatus.ASSIGNMENT_FAILED);
        result.setReasonDetails(reason);
        return result;
    }

    @Override
    @Transactional
    @Observed(name = "truckcar.allocation.matching", contextualName = "allocation rematching",
            lowCardinalityKeyValues = {"trigger", "reallocation"})
    public AllocationResultResponse processAutomaticReallocationForLoad(Long loadId, Set<String> excludedDriverIds,
                                                                        String trigger, UUID correlationId) {
        countAiRequest();
        Set<String> excluded = excludedDriverIds == null ? Set.of() : excludedDriverIds;
        Optional<FreightLoadDto> load;
        Optional<List<AvailableTruckDto>> availableTrucks;
        try {
            load = fetchLoadFromService(loadId);
            if (load.isEmpty()) {
                return pendingRecommendation(loadId,
                        "La carga no existe en Load-Allocation; la reasignación queda pendiente sin datos sintéticos");
            }
            availableTrucks = fetchAvailableTrucksFromService(loadId);
        } catch (RuntimeException unavailable) {
            return noCandidatesAvailable(loadId, excluded, trigger, correlationId, unavailable);
        }

        List<AllocationCandidateResponse> evaluatedList = availableTrucks.get().stream()
                .filter(truck -> !excluded.contains(truck.getDriverId()))
                .map(truck -> optimizer.evaluateTruck(truck, load.get()))
                .sorted(Comparator.comparing(AllocationCandidateResponse::getScore).reversed())
                .collect(Collectors.toList());
        AllocationCandidateResponse nextCandidate = evaluatedList.stream()
                .filter(AllocationCandidateResponse::getEligible)
                .findFirst()
                .orElse(null);
        AllocationDecision saved = repository.save(buildDecisionEntity(loadId, nextCandidate, evaluatedList.size()));

        if (nextCandidate == null) {
            publishNoCandidatesEvent(saved, excluded, trigger, correlationId);
            return AllocationResultResponse.builder()
                    .decisionId(saved.getId())
                    .loadId(loadId)
                    .status(AllocationStatus.NO_DRIVERS_AVAILABLE)
                    .reasonDetails("No hay candidatos elegibles después de excluir a los conductores " + excluded)
                    .evaluatedCandidates(evaluatedList)
                    .executedAt(saved.getExecutedAt())
                    .build();
        }

        publishScoreReadyEvent(saved, nextCandidate, trigger, correlationId);
        return AllocationResultResponse.builder()
                .decisionId(saved.getId())
                .loadId(loadId)
                .status(saved.getStatus())
                .selectedDriverId(nextCandidate.getDriverId())
                .selectedDriverName(nextCandidate.getDriverName())
                .selectedVehiclePlate(nextCandidate.getVehiclePlate())
                .matchingScore(nextCandidate.getScore())
                .deadheadDistanceKm(nextCandidate.getDeadheadDistanceKm())
                .reasonDetails(nextCandidate.getReason())
                .evaluatedCandidates(evaluatedList)
                .executedAt(saved.getExecutedAt())
                .build();
    }

    /**
     * Un conductor se liberó (entrega o cancelación): se reintenta el matching de las cargas en espera, de la más
     * antigua a la más reciente. Solo se lanza el matching real (que persiste la decisión) si hay algún candidato
     * elegible, y se detiene cuando ya no queda ningún conductor libre.
     */
    @Override
    public List<AllocationResultResponse> retryWaitingLoads(String trigger) {
        List<FreightLoadDto> waiting = fetchWaitingLoads();
        List<AllocationResultResponse> results = new ArrayList<>();
        for (FreightLoadDto load : waiting) {
            Optional<List<AvailableTruckDto>> fleet;
            try {
                fleet = fetchAvailableTrucksFromService(load.getId());
            } catch (RuntimeException unavailable) {
                log.warn("Reintento por {}: fleets-drivers no disponible ({}); las cargas siguen en espera",
                        trigger, unavailable.getMessage());
                break;
            }
            if (fleet.isEmpty() || fleet.get().stream().allMatch(truck -> truck.getBusyReason() != null)) {
                log.info("Reintento por {}: no quedan conductores libres; {} carga(s) siguen en espera",
                        trigger, waiting.size() - results.size());
                break;
            }
            if (!hasEligibleCandidate(load, fleet.get())) {
                continue;
            }
            AllocationResultResponse result = processAutomaticAllocationForLoad(load.getId(), null);
            log.info("Reintento por {}: carga #{} -> {} ({})", trigger, load.getId(), result.getStatus(),
                    result.getSelectedDriverId());
            results.add(result);
        }
        return results;
    }

    /** Cargas AVAILABLE sin propuesta (nunca encontraron conductor) o que quedaron sin candidatos, en orden FIFO. */
    private List<FreightLoadDto> fetchWaitingLoads() {
        try {
            FreightLoadDto[] loads = neighborCalls.loadAllocationQuery(() -> restClient.get()
                    .uri(loadAllocationServiceUrl + "/api/v1/loads/available")
                    .retrieve()
                    .body(FreightLoadDto[].class));
            if (loads == null) {
                return List.of();
            }
            return java.util.Arrays.stream(loads)
                    .filter(load -> load.getAssignmentStatus() == null
                            || WAITING_ASSIGNMENT_STATUSES.contains(load.getAssignmentStatus()))
                    .sorted(Comparator.comparing(FreightLoadDto::getId))
                    .limit(retryBatchSize)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("No se pudieron consultar las cargas en espera en {}: {}", loadAllocationServiceUrl, e.getMessage());
            return List.of();
        }
    }

    /** Evalúa en memoria (sin persistir) si algún camión libre y no descartado sirve para la carga. */
    private boolean hasEligibleCandidate(FreightLoadDto load, List<AvailableTruckDto> fleet) {
        Set<String> discarded = load.getDiscardedDriverIds() == null
                ? Set.of() : Set.copyOf(load.getDiscardedDriverIds());
        return fleet.stream()
                .filter(truck -> !discarded.contains(truck.getDriverId()))
                .map(truck -> optimizer.evaluateTruck(truck, load))
                .anyMatch(AllocationCandidateResponse::getEligible);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AllocationResultResponse> getDecisionsByLoad(Long loadId) {
        return repository.findByLoadIdOrderByExecutedAtDesc(loadId).stream()
                .map(d -> AllocationResultResponse.builder()
                        .decisionId(d.getId())
                        .loadId(d.getLoadId())
                        .status(d.getStatus())
                        .selectedDriverId(d.getSelectedDriverId())
                        .selectedDriverName(d.getSelectedDriverName())
                        .selectedVehiclePlate(d.getSelectedVehiclePlate())
                        .matchingScore(d.getMatchingScore())
                        .deadheadDistanceKm(d.getDeadheadDistanceKm())
                        .reasonDetails(d.getReasonDetails())
                        .executedAt(d.getExecutedAt())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Vacío si la carga no existe (404). Si Load-Allocation no responde (timeout, 5xx o circuito abierto) lanza la
     * excepción para que el llamador aplique noCandidatesAvailable.
     */
    private Optional<FreightLoadDto> fetchLoadFromService(Long loadId) {
        try {
            FreightLoadDto load = neighborCalls.loadAllocationQuery(() -> restClient.get()
                    .uri(loadAllocationServiceUrl + "/api/v1/loads/" + loadId)
                    .retrieve()
                    .body(FreightLoadDto.class));
            return Optional.ofNullable(load);
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        }
    }

    /**
     * Flota ACTIVO con los conductores ocupados marcados como no elegibles: se toma lo que reporta fleets-drivers
     * (cargas aceptadas o en ruta) y se completa con Load-Allocation, que es la fuente de verdad y además conoce
     * las propuestas vigentes. Si fleets-drivers no responde lanza la excepción (el llamador aplica el fallback).
     */
    private Optional<List<AvailableTruckDto>> fetchAvailableTrucksFromService(Long loadId) {
        List<FleetDriverStateDto> states = neighborCalls.fleetsDrivers(() -> restClient.get()
                .uri(fleetsDriversServiceUrl + "/api/v1/fleets-drivers/active")
                .retrieve()
                .body(new ParameterizedTypeReference<List<FleetDriverStateDto>>() {}));
        if (states == null) {
            return Optional.empty();
        }
        List<AvailableTruckDto> trucks = states.stream()
                .filter(FleetDriverStateDto::isAvailableForAllocation)
                .map(FleetDriverStateDto::toAvailableTruck)
                .collect(Collectors.toList());
        // Load-Allocation manda cuando responde: fleets-drivers puede ir un evento atrás (p. ej. aún no procesó
        // el trip.delivered que acaba de liberar al conductor). Su onTrip solo se usa si Load-Allocation no responde.
        fetchDriverCommitments(loadId).ifPresent(busy ->
                trucks.forEach(truck -> truck.setBusyReason(busy.get(truck.getDriverId()))));
        return Optional.of(trucks);
    }

    /**
     * Conductores comprometidos según Load-Allocation, sin contar la carga que se está asignando. Si no responde,
     * se sigue solo con fleets-drivers: Load-Allocation rechaza igualmente (409) asignar a un conductor ocupado.
     */
    private Optional<Map<String, String>> fetchDriverCommitments(Long loadId) {
        try {
            DriverCommitmentDto[] commitments = neighborCalls.loadAllocationQuery(() -> restClient.get()
                    .uri(loadAllocationServiceUrl + "/api/v1/loads/driver-commitments")
                    .retrieve()
                    .body(DriverCommitmentDto[].class));
            if (commitments == null) {
                return Optional.empty();
            }
            Map<String, String> busy = new HashMap<>();
            for (DriverCommitmentDto commitment : commitments) {
                if (commitment.getDriverId() != null && !Objects.equals(commitment.getLoadId(), loadId)) {
                    busy.putIfAbsent(commitment.getDriverId(), commitment.busyReason());
                }
            }
            return Optional.of(busy);
        } catch (Exception e) {
            log.warn("No se pudieron consultar los conductores ocupados en {}; se usa solo fleets-drivers: {}",
                    loadAllocationServiceUrl, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fallback del matching cuando fleets-drivers o Load-Allocation no responden (timeout, 5xx o circuito abierto).
     * En vez de lanzar (el consumidor de Kafka reintentaría y bloquearía la partición) se registra la decisión
     * NO_DRIVERS_AVAILABLE y se publica agent.no.candidates: Load-Allocation deja la carga en UNASSIGNED_NO_DRIVERS
     * (estado seguro) y el reintento programado de cargas en espera vuelve a intentarlo cuando los vecinos se recuperan.
     */
    AllocationResultResponse noCandidatesAvailable(Long loadId, Set<String> excludedDriverIds, String trigger,
                                                   UUID correlationId, Throwable cause) {
        String reason = "Servicios vecinos no disponibles (" + cause.getClass().getSimpleName()
                + "); la carga queda en espera para reintento";
        log.warn("Matching de la carga #{} aplazado: {}: {}", loadId, reason, cause.getMessage());
        Metrics.counter("truckcar.ai.fallback", "component", "fleet-matching",
                "reason", cause.getClass().getSimpleName()).increment();
        AllocationDecision saved = repository.save(AllocationDecision.builder()
                .loadId(loadId)
                .status(AllocationStatus.NO_DRIVERS_AVAILABLE)
                .reasonDetails(reason.length() > 250 ? reason.substring(0, 250) : reason)
                .evaluatedCandidatesCount(0)
                .executedAt(LocalDateTime.now())
                .build());
        publishNoCandidatesEvent(saved, excludedDriverIds, trigger, correlationId);
        return AllocationResultResponse.builder()
                .decisionId(saved.getId())
                .loadId(loadId)
                .status(AllocationStatus.NO_DRIVERS_AVAILABLE)
                .reasonDetails(reason)
                .executedAt(saved.getExecutedAt())
                .build();
    }

    /** Denominador de la tasa de fallbacks del dashboard: truckcar_ai_fallback_total / truckcar_ai_requests_total. */
    private static void countAiRequest() {
        Metrics.counter("truckcar.ai.requests", "component", "fleet-matching").increment();
    }

    private AllocationResultResponse pendingRecommendation(Long loadId, String reason) {
        return AllocationResultResponse.builder()
                .loadId(loadId)
                .status(AllocationStatus.RECOMMENDATION_PENDING)
                .reasonDetails(reason)
                .executedAt(LocalDateTime.now())
                .build();
    }

    /** Resultado de pedir a Load-Allocation que aplique la propuesta (PATCH /assign). */
    record AssignmentConfirmation(boolean accepted, boolean conflict, String detail) {
        static AssignmentConfirmation ok() { return new AssignmentConfirmation(true, false, null); }
        static AssignmentConfirmation conflict(String detail) { return new AssignmentConfirmation(false, true, detail); }
        static AssignmentConfirmation failed(String detail) { return new AssignmentConfirmation(false, false, detail); }
    }

    private AssignmentConfirmation confirmAssignmentInLoadService(Long loadId, String driverId, String driverName,
                                                                  String plate, UUID correlationId) {
        try {
            neighborCalls.loadAllocationCommand(() -> restClient.patch()
                    .uri(loadAllocationServiceUrl + "/api/v1/loads/" + loadId + "/assign")
                    .headers(headers -> {
                        // Load-Allocation solo deja asignar a COORDINADOR, ADMIN o al motor (SYSTEM). La llamada va por
                        // truckcar-network, no por el gateway, que descarta este encabezado si lo envía un cliente.
                        headers.set("X-Auth-Role", "SYSTEM");
                        if (correlationId != null) {
                            headers.set("X-Correlation-Id", correlationId.toString());
                        }
                    })
                    .body(Map.of(
                            "driverId", driverId,
                            "driverName", driverName,
                            "vehiclePlate", plate
                    ))
                    .retrieve()
                    .toBodilessEntity());
            log.info("Asignación confirmada en Load-Allocation para carga #{}", loadId);
            return AssignmentConfirmation.ok();
        } catch (HttpClientErrorException.Conflict e) {
            String detail = loadServiceMessage(e);
            log.warn("Load-Allocation rechazó la asignación de la carga #{}: {}", loadId, detail);
            return AssignmentConfirmation.conflict(detail);
        } catch (Exception e) {
            log.error("No se pudo confirmar la asignación de la carga #{} en Load-Allocation", loadId, e);
            return AssignmentConfirmation.failed(e.getMessage());
        }
    }

    /** Extrae el campo "message" del error JSON de Load-Allocation; si no existe, devuelve el cuerpo crudo. */
    private String loadServiceMessage(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : body;
    }

    private AllocationDecision buildDecisionEntity(Long loadId, AllocationCandidateResponse bestCandidate, int candidatesCount) {
        if (bestCandidate != null) {
            return AllocationDecision.builder()
                    .loadId(loadId)
                    .selectedDriverId(bestCandidate.getDriverId())
                    .selectedDriverName(bestCandidate.getDriverName())
                    .selectedVehiclePlate(bestCandidate.getVehiclePlate())
                    .matchingScore(bestCandidate.getScore())
                    .deadheadDistanceKm(bestCandidate.getDeadheadDistanceKm())
                    .status(AllocationStatus.SUCCESSFUL)
                    .reasonDetails(bestCandidate.getReason())
                    .evaluatedCandidatesCount(candidatesCount)
                    .executedAt(LocalDateTime.now())
                    .build();
        } else {
            return AllocationDecision.builder()
                    .loadId(loadId)
                    .status(AllocationStatus.NO_DRIVERS_AVAILABLE)
                    .reasonDetails("Ningún vehículo cumplió los requisitos de capacidad, tipo o distancia máxima")
                    .evaluatedCandidatesCount(candidatesCount)
                    .executedAt(LocalDateTime.now())
                    .build();
        }
    }

    private void publishAllocationKafkaEvent(AllocationDecision d) {
        try {
            FleetAllocationCompletedEvent event = FleetAllocationCompletedEvent.builder()
                    .decisionId(d.getId())
                    .loadId(d.getLoadId())
                    .driverId(d.getSelectedDriverId())
                    .driverName(d.getSelectedDriverName())
                    .vehiclePlate(d.getSelectedVehiclePlate())
                    .matchingScore(d.getMatchingScore())
                    .deadheadDistanceKm(d.getDeadheadDistanceKm())
                    .status(d.getStatus().name())
                    .timestamp(LocalDateTime.now())
                    .build();

            kafkaTemplate.send(allocationTopic, d.getLoadId().toString(), event);
        } catch (Throwable t) {
            log.warn("No se pudo publicar evento de asignación en Kafka: {}", t.getMessage());
        }
    }

    private void publishScoreReadyEvent(AllocationDecision decision,
                                        AllocationCandidateResponse candidate,
                                        String trigger,
                                        UUID correlationId) {
            AgentScoreReadyEvent payload = AgentScoreReadyEvent.builder()
                    .loadId(decision.getLoadId())
                    .decisionId(decision.getId())
                    .driverId(candidate.getDriverId())
                    .driverName(candidate.getDriverName())
                    .vehiclePlate(candidate.getVehiclePlate())
                    .score(candidate.getScore())
                    .deadheadDistanceKm(candidate.getDeadheadDistanceKm())
                    .explanation(candidate.getReason())
                    .trigger(trigger)
                    .build();
            kafkaTemplate.send(allocationTopic, decision.getLoadId().toString(),
                    envelope("agent.score.ready", decision.getLoadId(), payload, correlationId));
            log.info("Puntaje de reasignación listo para carga #{} y conductor {}",
                    decision.getLoadId(), candidate.getDriverId());
    }

    private void publishNoCandidatesEvent(AllocationDecision decision, Set<String> excludedDriverIds, String trigger,
                                          UUID correlationId) {
        Map<String, Object> payload = Map.of(
                "loadId", decision.getLoadId(),
                "decisionId", decision.getId(),
                "excludedDriverIds", List.copyOf(excludedDriverIds),
                "trigger", trigger == null ? "" : trigger
        );
        kafkaTemplate.send(allocationTopic, decision.getLoadId().toString(),
                envelope("agent.no.candidates", decision.getLoadId(), payload, correlationId));
        log.info("Sin candidatos para la carga #{} tras excluir {}", decision.getLoadId(), excludedDriverIds);
    }

    /** Conserva el correlationId del evento recibido; solo si falta (llamada REST manual) se deriva del loadId. */
    private <T> EventEnvelope<T> envelope(String eventType, Long loadId, T payload, UUID correlationId) {
        return EventEnvelope.<T>builder()
                .eventId(UUID.randomUUID())
                .eventType(eventType)
                .occurredAt(Instant.now())
                .correlationId(correlationId != null ? correlationId : UUID.nameUUIDFromBytes(
                        String.valueOf(loadId).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .version(1)
                .producer("ai-powered-fleet-allocation")
                .payload(payload)
                .build();
    }
}