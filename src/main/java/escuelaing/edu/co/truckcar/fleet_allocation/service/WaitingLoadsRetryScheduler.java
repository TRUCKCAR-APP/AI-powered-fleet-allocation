package escuelaing.edu.co.truckcar.fleet_allocation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reintento periódico de las cargas en espera (sin propuesta o UNASSIGNED_NO_DRIVERS). Recupera las cargas que el
 * fallback noCandidatesAvailable aplazó cuando un vecino no respondía, aunque no se libere ningún conductor.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "truckcar.allocation.scheduled-retry.enabled", havingValue = "true", matchIfMissing = true)
public class WaitingLoadsRetryScheduler {

    static final String TRIGGER = "scheduled-retry";

    private final IFleetAllocationService allocationService;

    @Scheduled(initialDelayString = "${truckcar.allocation.scheduled-retry.initial-delay-ms:60000}",
            fixedDelayString = "${truckcar.allocation.scheduled-retry.interval-ms:120000}")
    public void retryWaitingLoads() {
        try {
            int matched = allocationService.retryWaitingLoads(TRIGGER).size();
            if (matched > 0) {
                log.info("Reintento programado: {} carga(s) en espera volvieron al matching", matched);
            }
        } catch (RuntimeException e) {
            log.warn("Reintento programado de cargas en espera fallido: {}", e.getMessage());
        }
    }
}
