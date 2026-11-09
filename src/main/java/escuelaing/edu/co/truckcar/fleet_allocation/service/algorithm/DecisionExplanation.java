package escuelaing.edu.co.truckcar.fleet_allocation.service.algorithm;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationCandidateResponse;

import java.util.List;
import java.util.Locale;

/**
 * Síntesis en lenguaje claro de una decisión de asignación, para la torre de control. La decisión la toma el motor
 * determinista (FleetScoringOptimizer y el filtro de gálibo/peso en PostGIS), no un modelo de IA; este texto solo
 * cuenta qué se eligió y por qué se descartó a los demás.
 */
public final class DecisionExplanation {

    static final int MAX_LENGTH = 2000;
    private static final int MAX_DISCARDS_LISTED = 5;
    private static final Locale ES = Locale.forLanguageTag("es-CO");

    private DecisionExplanation() {
    }

    public static String of(AllocationCandidateResponse selected, List<AllocationCandidateResponse> evaluated) {
        List<AllocationCandidateResponse> all = evaluated == null ? List.of() : evaluated;
        StringBuilder text = new StringBuilder();
        if (selected != null) {
            text.append("Asignado por algoritmo determinista (Cercanía + Capacidad + Calificación): ")
                    .append(label(selected));
            if (selected.getScore() != null) {
                text.append(String.format(ES, ", puntaje %.2f", selected.getScore()));
            }
            if (selected.getDeadheadDistanceKm() != null) {
                text.append(String.format(ES, ", a %.1f km de la recogida", selected.getDeadheadDistanceKm()));
            }
            if (selected.getCapacityUtilizationPercent() != null) {
                text.append(String.format(ES, ", %.0f %% de la capacidad", selected.getCapacityUtilizationPercent()));
            }
            text.append('.');
            long otherEligible = all.stream()
                    .filter(c -> Boolean.TRUE.equals(c.getEligible()) && c != selected)
                    .count();
            if (otherEligible > 0) {
                text.append(' ').append(otherEligible).append(otherEligible == 1
                        ? " candidato elegible con menor puntaje." : " candidatos elegibles con menor puntaje.");
            }
        } else {
            text.append("Sin asignación: ningún candidato elegible entre ").append(all.size())
                    .append(all.size() == 1 ? " evaluado." : " evaluados.");
        }

        List<AllocationCandidateResponse> discarded = all.stream()
                .filter(c -> !Boolean.TRUE.equals(c.getEligible()))
                .toList();
        discarded.stream().limit(MAX_DISCARDS_LISTED).forEach(c -> text.append(" Candidato ").append(label(c))
                .append(" descartado").append(reason(c.getReason())).append('.'));
        if (discarded.size() > MAX_DISCARDS_LISTED) {
            text.append(" Otros ").append(discarded.size() - MAX_DISCARDS_LISTED).append(" descartados.");
        }
        return text.length() <= MAX_LENGTH ? text.toString() : text.substring(0, MAX_LENGTH - 1) + "…";
    }

    private static String label(AllocationCandidateResponse candidate) {
        String plate = candidate.getVehiclePlate();
        String name = candidate.getDriverName();
        if (plate != null && name != null) {
            return plate + " (" + name + ")";
        }
        return plate != null ? plate : name != null ? name : candidate.getDriverId();
    }

    /** "Incompatible por gálibo…" se lee "descartado por gálibo…"; el resto, "descartado: …". */
    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        String trimmed = reason.trim();
        if (trimmed.startsWith("Incompatible por ")) {
            return " por " + trimmed.substring("Incompatible por ".length());
        }
        boolean acronym = trimmed.length() > 1 && Character.isUpperCase(trimmed.charAt(1));
        return ": " + (acronym ? trimmed : Character.toLowerCase(trimmed.charAt(0)) + trimmed.substring(1));
    }
}
