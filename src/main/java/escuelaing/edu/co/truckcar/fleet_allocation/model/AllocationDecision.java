package escuelaing.edu.co.truckcar.fleet_allocation.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "allocation_decisions", indexes = {
        @Index(name = "idx_decision_load", columnList = "load_id"),
        @Index(name = "idx_decision_driver", columnList = "selected_driver_id")
})
public class AllocationDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "load_id", nullable = false)
    private Long loadId;

    @Column(name = "selected_driver_id")
    private String selectedDriverId;

    @Column(name = "selected_driver_name")
    private String selectedDriverName;

    @Column(name = "selected_vehicle_plate")
    private String selectedVehiclePlate;

    @Column(name = "matching_score")
    private Double matchingScore;

    @Column(name = "deadhead_distance_km")
    private Double deadheadDistanceKm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AllocationStatus status;

    @Column(name = "reason_details", length = 1000)
    private String reasonDetails;

    /** Síntesis en lenguaje claro para la torre de control (ver DecisionExplanation). */
    @Column(name = "decision_explanation", length = 2000)
    private String explanation;

    @Column(name = "evaluated_candidates_count")
    private Integer evaluatedCandidatesCount;

    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;
}