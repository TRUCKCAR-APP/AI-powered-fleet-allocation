package escuelaing.edu.co.truckcar.fleet_allocation.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Punto único por el que pasan las llamadas HTTP a los servicios vecinos, con @CircuitBreaker y @Retry (instancias de
 * application.yml). Retry envuelve al CircuitBreaker: cada intento cuenta en el circuito y, abierto, no se reintenta.
 * Los métodos lanzan la excepción final; FleetAllocationService la convierte en su fallback (noCandidatesAvailable).
 */
@Component
public class NeighborServiceCalls {

    static final String FLEETS_DRIVERS = "fleetsDrivers";
    static final String LOAD_ALLOCATION = "loadAllocation";

    @CircuitBreaker(name = FLEETS_DRIVERS)
    @Retry(name = FLEETS_DRIVERS)
    public <T> T fleetsDrivers(Supplier<T> call) {
        return call.get();
    }

    @CircuitBreaker(name = LOAD_ALLOCATION)
    @Retry(name = LOAD_ALLOCATION)
    public <T> T loadAllocationQuery(Supplier<T> call) {
        return call.get();
    }

    /**
     * Sin Retry: PATCH /assign no es idempotente. Si la respuesta se perdió tras aplicarse, un reintento recibiría
     * 409 y la decisión se marcaría como rechazada por error.
     */
    @CircuitBreaker(name = LOAD_ALLOCATION)
    public <T> T loadAllocationCommand(Supplier<T> call) {
        return call.get();
    }
}
