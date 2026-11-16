package escuelaing.edu.co.truckcar.fleet_allocation.model;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationCandidateResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Un candidato evaluado en una decisión, tal como lo vio el motor (tabla allocation_decision_candidates). */
@Embeddable
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluatedCandidate {

    static final int MAX_REASON = 500;

    @Column(name = "driver_id")
    private String driverId;
    @Column(name = "driver_name")
    private String driverName;
    @Column(name = "vehicle_plate")
    private String vehiclePlate;
    @Column(name = "truck_type", length = 60)
    private String truckType;
    @Column(name = "eligible", nullable = false)
    private boolean eligible;
    @Column(name = "score")
    private Double score;
    @Column(name = "proximity_points")
    private Double proximityPoints;
    @Column(name = "capacity_points")
    private Double capacityPoints;
    @Column(name = "rating_points")
    private Double ratingPoints;
    @Column(name = "deadhead_distance_km")
    private Double deadheadDistanceKm;
    @Column(name = "capacity_utilization_percent")
    private Double capacityUtilizationPercent;
    @Column(name = "reason", length = MAX_REASON)
    private String reason;

    public static EvaluatedCandidate from(AllocationCandidateResponse c) {
        String reason = c.getReason();
        return EvaluatedCandidate.builder()
                .driverId(c.getDriverId())
                .driverName(c.getDriverName())
                .vehiclePlate(c.getVehiclePlate())
                .truckType(c.getTruckType() == null || c.getTruckType().length() <= 60 ? c.getTruckType() : c.getTruckType().substring(0, 60))
                .eligible(Boolean.TRUE.equals(c.getEligible()))
                .score(c.getScore())
                .proximityPoints(c.getProximityPoints())
                .capacityPoints(c.getCapacityPoints())
                .ratingPoints(c.getRatingPoints())
                .deadheadDistanceKm(c.getDeadheadDistanceKm())
                .capacityUtilizationPercent(c.getCapacityUtilizationPercent())
                .reason(reason == null || reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON - 1) + "…")
                .build();
    }

    public AllocationCandidateResponse toResponse() {
        return AllocationCandidateResponse.builder()
                .driverId(driverId)
                .driverName(driverName)
                .vehiclePlate(vehiclePlate)
                .truckType(truckType)
                .eligible(eligible)
                .score(score)
                .proximityPoints(proximityPoints)
                .capacityPoints(capacityPoints)
                .ratingPoints(ratingPoints)
                .deadheadDistanceKm(deadheadDistanceKm)
                .capacityUtilizationPercent(capacityUtilizationPercent)
                .reason(reason)
                .build();
    }
}
