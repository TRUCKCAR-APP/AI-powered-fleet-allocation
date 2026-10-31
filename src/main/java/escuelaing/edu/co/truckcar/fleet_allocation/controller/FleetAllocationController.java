package escuelaing.edu.co.truckcar.fleet_allocation.controller;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.request.ManualAllocationRequest;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationResultResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.exception.AllocationAccessDeniedException;
import escuelaing.edu.co.truckcar.fleet_allocation.service.IFleetAllocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/fleet-allocation")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FleetAllocationController {

    private static final String AUTH_ROLE_HEADER = "X-Auth-Role";
    private static final Set<String> CONTROL_TOWER_ROLES = Set.of("COORDINADOR", "ADMIN");

    private final IFleetAllocationService service;

    @PostMapping("/optimize")
    public ResponseEntity<AllocationResultResponse> optimize(
            @Valid @RequestBody ManualAllocationRequest request,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        requireControlTower(authenticatedRole);
        return ResponseEntity.ok(service.processManualOptimization(request));
    }

    @PostMapping("/match/{loadId}")
    public ResponseEntity<AllocationResultResponse> matchLoadById(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        requireControlTower(authenticatedRole);
        AllocationResultResponse result = service.processAutomaticAllocationForLoad(loadId);
        // La decisión se devuelve siempre; el código HTTP refleja si Load-Allocation la aplicó.
        HttpStatus status = switch (result.getStatus()) {
            case ASSIGNMENT_CONFLICT -> HttpStatus.CONFLICT;
            case ASSIGNMENT_FAILED -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(result);
    }

    /** El historial incluye a los conductores evaluados y sus puntajes: tampoco es visible para conductores. */
    @GetMapping("/history/{loadId}")
    public ResponseEntity<List<AllocationResultResponse>> getHistoryByLoad(
            @PathVariable Long loadId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String authenticatedRole) {
        requireControlTower(authenticatedRole);
        return ResponseEntity.ok(service.getDecisionsByLoad(loadId));
    }

    /**
     * El matching manual y la optimización son de la torre de control. El matching automático no pasa por aquí:
     * lo disparan los eventos de Kafka. X-Auth-Role lo fija el api-gateway desde el JWT.
     */
    private static void requireControlTower(String authenticatedRole) {
        if (authenticatedRole == null || authenticatedRole.isBlank()) {
            throw new AllocationAccessDeniedException("Se requiere un rol autenticado para operar el motor de asignación", true);
        }
        if (!CONTROL_TOWER_ROLES.contains(authenticatedRole)) {
            throw new AllocationAccessDeniedException("El rol " + authenticatedRole
                    + " no puede operar el motor de asignación (solo COORDINADOR o ADMIN)", false);
        }
    }
}
