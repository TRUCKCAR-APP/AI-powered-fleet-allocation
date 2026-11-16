package escuelaing.edu.co.truckcar.fleet_allocation.service;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.request.ManualAllocationRequest;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationResultResponse;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface IFleetAllocationService {
    AllocationResultResponse processManualOptimization(ManualAllocationRequest request);
    AllocationResultResponse processAutomaticAllocationForLoad(Long loadId, UUID correlationId);
    AllocationResultResponse processAutomaticReallocationForLoad(Long loadId, Set<String> excludedDriverIds, String trigger,
                                                                 UUID correlationId);

    /** Disparo manual por REST: sin evento de origen, la correlación se deriva del loadId. */
    default AllocationResultResponse processAutomaticAllocationForLoad(Long loadId) {
        return processAutomaticAllocationForLoad(loadId, null);
    }

    /** Matching manual (POST /match/{loadId}): lanza LoadNotFoundException si la carga no existe en Load-Allocation. */
    AllocationResultResponse matchLoadOnDemand(Long loadId);
    List<AllocationResultResponse> getDecisionsByLoad(Long loadId);

    /** Reintenta el matching de las cargas en espera cuando un conductor se libera (trip.delivered, load.cancelled). */
    List<AllocationResultResponse> retryWaitingLoads(String trigger);
}