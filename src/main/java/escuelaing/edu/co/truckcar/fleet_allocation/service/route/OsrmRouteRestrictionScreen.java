package escuelaing.edu.co.truckcar.fleet_allocation.service.route;

import com.fasterxml.jackson.databind.JsonNode;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.AvailableTruckDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Gálibo y peso de la ruta de la carga para cada camión candidato:
 * <ol>
 *   <li>OSRM da hasta 3 rutas entre el origen y el destino (alternatives=true), las mismas que evalúa la navegación del
 *       conductor. Se guardan en memoria por carga para no repetir la consulta en cada reintento.</li>
 *   <li>Por cada combinación de altura y peso bruto con la carga (tara + carga a bordo + peso de la carga) se valida cada
 *       ruta con POST /api/v1/restrictions/validate-route de Routing-Restrictions.</li>
 *   <li>El camión queda incompatible solo si <b>todas</b> las rutas incumplen alguna restricción: si existe un desvío apto,
 *       la navegación lo elige. El motivo sale de la ruta con menos incumplimientos.</li>
 * </ol>
 * Sin OSRM o sin Routing-Restrictions no se descarta a nadie (no se inventa una incompatibilidad) y se cuenta en
 * truckcar_ai_fallback_total{component="route-restrictions"}.
 */
@Slf4j
@Component
public class OsrmRouteRestrictionScreen implements RouteRestrictionScreen {

    static final String REASON_PREFIX = "Incompatible por gálibo/peso en la ruta";
    private static final Locale ES_CO = Locale.forLanguageTag("es-CO");
    /** Routing-Restrictions acepta hasta 2000 puntos; con 400 sobra para cruzar puentes a 60 m de la línea. */
    private static final int MAX_PATH_POINTS = 400;
    private static final int MAX_ALTERNATIVES = 3;
    private static final int ROUTE_CACHE_SIZE = 200;

    private final RestClient restClient;
    private final String osrmUrl;
    private final String restrictionsUrl;
    private final Map<String, List<List<double[]>>> routeCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<List<double[]>>> eldest) {
                    return size() > ROUTE_CACHE_SIZE;
                }
            });

    public OsrmRouteRestrictionScreen(
            RestClient restClient,
            @Value("${truckcar.routing.osrm-url:https://router.project-osrm.org}") String osrmUrl,
            @Value("${truckcar.services.routing-restrictions-url:http://localhost:8084}") String restrictionsUrl) {
        this.restClient = restClient;
        this.osrmUrl = osrmUrl.replaceAll("/+$", "");
        this.restrictionsUrl = restrictionsUrl.replaceAll("/+$", "");
    }

    @Override
    public void annotate(FreightLoadDto load, List<AvailableTruckDto> trucks) {
        if (load == null || trucks == null || trucks.isEmpty() || !hasEndpoints(load)) {
            return;
        }
        List<AvailableTruckDto> checkable = trucks.stream().filter(OsrmRouteRestrictionScreen::checkable).toList();
        if (checkable.isEmpty()) {
            return;
        }
        List<List<double[]>> routes;
        try {
            routes = routesFor(load);
        } catch (RuntimeException e) {
            unverified("osrm", load, e);
            return;
        }
        if (routes.isEmpty()) {
            return;
        }
        // Camiones con la misma altura y peso bruto comparten la respuesta: una validación por combinación y ruta.
        Map<String, Optional<String>> byProfile = new HashMap<>();
        for (AvailableTruckDto truck : checkable) {
            double height = truck.getHeightMeters();
            double gross = grossWithLoad(truck, load);
            String profile = height + "|" + gross;
            Optional<String> reason;
            try {
                reason = byProfile.computeIfAbsent(profile, p -> incompatibility(routes, height, gross));
            } catch (RuntimeException e) {
                unverified("routing-restrictions", load, e);
                return;
            }
            reason.ifPresent(truck::setRouteRestrictionReason);
        }
    }

    /** El motivo de la ruta con menos incumplimientos, o vacío si alguna ruta no incumple ninguna restricción. */
    Optional<String> incompatibility(List<List<double[]>> routes, double heightMeters, double grossWeightTons) {
        JsonNode best = null;
        for (List<double[]> path : routes) {
            JsonNode result = restClient.post()
                    .uri(restrictionsUrl + "/api/v1/restrictions/validate-route")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("heightMeters", heightMeters, "grossWeightTons", grossWeightTons, "path", toLatLng(path)))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode blocking = result == null ? null : result.get("blocking");
            if (blocking == null || !blocking.isArray() || blocking.isEmpty()) {
                return Optional.empty();
            }
            if (best == null || blocking.size() < best.size()) {
                best = blocking;
            }
        }
        return best == null ? Optional.empty() : Optional.of(reason(best.get(0), heightMeters, grossWeightTons));
    }

    static String reason(JsonNode hit, double heightMeters, double grossWeightTons) {
        String kind = hit.path("kind").asText("");
        JsonNode restriction = hit.path("restriction");
        return switch (kind) {
            case "GALIBO_ALTURA" -> String.format(ES_CO, "%s (Límite: %.2f m, Vehículo: %.2f m)", REASON_PREFIX,
                    restriction.path("maxClearanceMeters").asDouble(), heightMeters);
            case "PESO_BRUTO" -> String.format(ES_CO, "%s (Límite: %.1f t, Vehículo: %.1f t)", REASON_PREFIX,
                    restriction.path("maxGrossWeightTons").asDouble(), grossWeightTons);
            case "PESO_POR_EJE" -> String.format(ES_CO, "%s (Límite por eje: %.1f t)", REASON_PREFIX,
                    restriction.path("maxAxleWeightTons").asDouble());
            case "BLOQUEO_TOTAL" -> REASON_PREFIX + " (Paso cerrado: " + restriction.path("title").asText("vía cerrada") + ")";
            default -> REASON_PREFIX + " (" + hit.path("reason").asText(kind) + ")";
        };
    }

    /** Rutas de OSRM entre el origen y el destino, como [lat, lng], muestreadas a MAX_PATH_POINTS. */
    List<List<double[]>> routesFor(FreightLoadDto load) {
        String key = String.format(Locale.ROOT, "%.5f,%.5f;%.5f,%.5f", load.getOriginLongitude(), load.getOriginLatitude(),
                load.getDestinationLongitude(), load.getDestinationLatitude());
        List<List<double[]>> cached = routeCache.get(key);
        if (cached != null) {
            return cached;
        }
        JsonNode response = restClient.get()
                .uri(osrmUrl + "/route/v1/driving/" + key + "?overview=full&geometries=geojson&alternatives=true")
                .retrieve()
                .body(JsonNode.class);
        List<List<double[]>> routes = new ArrayList<>();
        JsonNode list = response == null ? null : response.get("routes");
        if (list != null) {
            for (JsonNode route : list) {
                JsonNode coordinates = route.path("geometry").path("coordinates");
                if (coordinates.isArray() && coordinates.size() > 1) {
                    routes.add(sample(coordinates));
                }
                if (routes.size() == MAX_ALTERNATIVES) {
                    break;
                }
            }
        }
        routeCache.put(key, routes);
        return routes;
    }

    /** Hasta MAX_PATH_POINTS puntos [lat, lng] de la geometría GeoJSON [lng, lat], siempre con el primero y el último. */
    private static List<double[]> sample(JsonNode coordinates) {
        int n = coordinates.size();
        int count = Math.min(n, MAX_PATH_POINTS);
        List<double[]> path = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            JsonNode point = coordinates.get(count == n ? i : (int) Math.round(i * (n - 1) / (double) (count - 1)));
            path.add(new double[] {point.get(1).asDouble(), point.get(0).asDouble()});
        }
        return path;
    }

    private static List<List<Double>> toLatLng(List<double[]> path) {
        return path.stream().map(p -> List.of(p[0], p[1])).toList();
    }

    /** Peso bruto que tendría el camión con esta carga: tara + lo que ya lleva a bordo + el peso de la carga. */
    static double grossWithLoad(AvailableTruckDto truck, FreightLoadDto load) {
        double onBoard = truck.getOnBoardTons() == null ? 0.0 : truck.getOnBoardTons();
        double cargo = load.getWeightTons() == null ? 0.0 : load.getWeightTons();
        return truck.getTareWeightTons() + onBoard + cargo;
    }

    /** Solo se consulta por quien podría recibir la carga y tiene altura y tara registradas. */
    private static boolean checkable(AvailableTruckDto truck) {
        return truck.getBusyReason() == null && Boolean.TRUE.equals(truck.getAccountVerified())
                && truck.getHeightMeters() != null && truck.getTareWeightTons() != null;
    }

    private static boolean hasEndpoints(FreightLoadDto load) {
        return load.getOriginLatitude() != null && load.getOriginLongitude() != null
                && load.getDestinationLatitude() != null && load.getDestinationLongitude() != null;
    }

    private static void unverified(String reason, FreightLoadDto load, RuntimeException e) {
        log.warn("No se pudo verificar gálibo y peso de la ruta de la carga #{} ({}): {}; no se descarta a nadie por ruta",
                load.getId(), reason, e.getMessage());
        Metrics.counter("truckcar.ai.fallback", "component", "route-restrictions", "reason", reason).increment();
    }
}
