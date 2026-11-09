package escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationCandidateResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** La torre lee en lenguaje claro por qué el motor determinista eligió a un conductor y descartó a los demás. */
class DecisionExplanationTest {

    private static AllocationCandidateResponse candidate(String plate, String name, boolean eligible, String reason) {
        return AllocationCandidateResponse.builder().vehiclePlate(plate).driverName(name).driverId(plate)
                .eligible(eligible).reason(reason).score(eligible ? 0.8234 : 0.0).deadheadDistanceKm(12.43)
                .capacityUtilizationPercent(73.5).build();
    }

    @Test
    void theWinnerIsDescribedWithItsFiguresAndEachDiscardWithItsReason() {
        AllocationCandidateResponse winner = candidate("TRK-101", "Ana", true, "Candidato idóneo");
        String text = DecisionExplanation.of(winner, List.of(winner,
                candidate("TRK-202", "Luis", true, "Candidato idóneo"),
                candidate("ALT-410", "Pedro", false, "Incompatible por gálibo/peso en la ruta (Límite: 3,80 m, Vehículo: 4,10 m)"),
                candidate("RUN-999", "Sara", false, "RUNT no vigente: el vehículo no puede recibir cargas")));

        assertThat(text).isEqualTo("Asignado por algoritmo determinista (Cercanía + Capacidad + Calificación): "
                + "TRK-101 (Ana), puntaje 0,82, a 12,4 km de la recogida, 74 % de la capacidad. "
                + "1 candidato elegible con menor puntaje. "
                + "Candidato ALT-410 (Pedro) descartado por gálibo/peso en la ruta (Límite: 3,80 m, Vehículo: 4,10 m). "
                + "Candidato RUN-999 (Sara) descartado: RUNT no vigente: el vehículo no puede recibir cargas.");
    }

    @Test
    void withoutWinnerItSaysSoAndListsAtMostFiveDiscards() {
        List<AllocationCandidateResponse> evaluated = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            evaluated.add(candidate("P-" + i, null, false, "Capacidad insuficiente"));
        }

        String text = DecisionExplanation.of(null, evaluated);

        assertThat(text).startsWith("Sin asignación: ningún candidato elegible entre 7 evaluados.")
                .contains("Candidato P-5 descartado: capacidad insuficiente.")
                .doesNotContain("P-6 descartado")
                .endsWith("Otros 2 descartados.");
    }

    @Test
    void anEmptyEvaluationStillProducesAReadableSentence() {
        assertThat(DecisionExplanation.of(null, List.of()))
                .isEqualTo("Sin asignación: ningún candidato elegible entre 0 evaluados.");
        assertThat(DecisionExplanation.of(null, null))
                .isEqualTo("Sin asignación: ningún candidato elegible entre 0 evaluados.");
    }
}
