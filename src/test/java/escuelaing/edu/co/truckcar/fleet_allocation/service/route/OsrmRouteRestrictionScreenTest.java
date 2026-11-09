package escuelaing.edu.co.truckcar.fleet_allocation.service.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.AvailableTruckDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * El filtro consulta las rutas de OSRM y las valida contra Routing-Restrictions con la altura y el peso bruto que tendría
 * cada camión con la carga. Servidor HTTP simulado (MockRestServiceServer): las peticiones son las reales.
 */
class OsrmRouteRestrictionScreenTest {

    private static final String OSRM = "http://osrm.test";
    private static final String RESTRICTIONS = "http://restrictions.test";
    // Dos rutas Bogotá → Tunja: la principal pasa por (4.70,-74.05) y la alterna por (4.75,-73.90).
    private static final String TWO_ROUTES = """
            {"routes": [
              {"geometry": {"coordinates": [[-74.0817, 4.6097], [-74.05, 4.70], [-73.3678, 5.5353]]}},
              {"geometry": {"coordinates": [[-74.0817, 4.6097], [-73.90, 4.75], [-73.3678, 5.5353]]}}
            ]}""";
    private static final String BRIDGE = """
            {"passable": false, "blocking": [{"kind": "GALIBO_ALTURA", "alongRouteMeters": 8000,
              "restriction": {"id": 3, "title": "Puente peatonal", "maxClearanceMeters": 3.8}}], "warnings": []}""";
    private static final String WEIGHT = """
            {"passable": false, "blocking": [{"kind": "PESO_BRUTO", "alongRouteMeters": 20000,
              "restriction": {"id": 4, "title": "Puente de la quebrada", "maxGrossWeightTons": 32.0}}], "warnings": []}""";
    private static final String CLEAR = "{\"passable\": true, \"blocking\": [], \"warnings\": []}";

    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private OsrmRouteRestrictionScreen screen;
    private final List<JsonNode> validations = new ArrayList<>();

    private static final FreightLoadDto LOAD = FreightLoadDto.builder().id(88L).weightTons(25.0)
            .originLatitude(4.6097).originLongitude(-74.0817).destinationLatitude(5.5353).destinationLongitude(-73.3678).build();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        screen = new OsrmRouteRestrictionScreen(builder.build(), OSRM, RESTRICTIONS);
    }

    private static AvailableTruckDto truck(String id, double height, double tare) {
        return AvailableTruckDto.builder().driverId(id).plate("PL-" + id).truckType("TRACTOMULA_3S3")
                .accountVerified(true).heightMeters(height).tareWeightTons(tare).onBoardTons(0.0).capacityTons(34.0).build();
    }

    private void osrmReturns(String body, ExpectedCount times) {
        server.expect(times, requestToUriTemplate(OSRM + "/route/v1/driving/{coords}?overview=full&geometries=geojson&alternatives=true",
                        "-74.08170,4.60970;-73.36780,5.53530"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    /** validate-route responde según el cuerpo (altura, peso y por cuál ruta pasa). */
    private void restrictionsAnswer(Function<JsonNode, String> answer) {
        server.expect(ExpectedCount.manyTimes(), requestTo(RESTRICTIONS + "/api/v1/restrictions/validate-route"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    synchronized (validations) {
                        validations.add(body);
                    }
                    return withSuccess(answer.apply(body), MediaType.APPLICATION_JSON).createResponse(request);
                });
    }

    private static boolean viaPrincipal(JsonNode body) {
        return body.get("path").get(1).get(1).asDouble() == -74.05;
    }

    @Test
    void aTruckThatEveryRouteRejectsIsMarkedWithTheLimitAndItsHeight() {
        osrmReturns(TWO_ROUTES, ExpectedCount.once());
        // Las dos rutas pasan bajo un puente de 3,80 m: solo afecta a camiones más altos.
        restrictionsAnswer(body -> body.get("heightMeters").asDouble() > 3.8 ? BRIDGE : CLEAR);
        AvailableTruckDto tall = truck("alto", 4.1, 15.0);
        AvailableTruckDto low = truck("bajo", 3.5, 12.0);

        screen.annotate(LOAD, List.of(tall, low));

        assertThat(tall.getRouteRestrictionReason())
                .isEqualTo("Incompatible por gálibo/peso en la ruta (Límite: 3,80 m, Vehículo: 4,10 m)");
        assertThat(low.getRouteRestrictionReason()).isNull();
        assertThat(validations).allSatisfy(v -> assertThat(v.get("path").size()).isEqualTo(3));
        assertThat(validations.get(0).get("path").get(0).get(0).asDouble()).as("[lat, lng]").isEqualTo(4.6097);
    }

    @Test
    void ifOneAlternativeAvoidsTheBridgeTheTruckStaysEligible() {
        osrmReturns(TWO_ROUTES, ExpectedCount.once());
        restrictionsAnswer(body -> viaPrincipal(body) ? BRIDGE : CLEAR);
        AvailableTruckDto tall = truck("alto", 4.1, 15.0);

        screen.annotate(LOAD, List.of(tall));

        assertThat(tall.getRouteRestrictionReason()).as("la navegación tomará la alterna").isNull();
    }

    @Test
    void theGrossWeightIncludesTareCargoOnBoardAndTheLoad() {
        osrmReturns(TWO_ROUTES, ExpectedCount.once());
        restrictionsAnswer(body -> body.get("grossWeightTons").asDouble() > 32.0 ? WEIGHT : CLEAR);
        AvailableTruckDto heavy = truck("pesado", 3.9, 15.0);   // 15 + 25 = 40 t
        heavy.setOnBoardTons(0.0);
        AvailableTruckDto light = truck("liviano", 3.9, 5.0);   // 5 + 25 = 30 t

        screen.annotate(LOAD, List.of(heavy, light));

        assertThat(heavy.getRouteRestrictionReason())
                .isEqualTo("Incompatible por gálibo/peso en la ruta (Límite: 32,0 t, Vehículo: 40,0 t)");
        assertThat(light.getRouteRestrictionReason()).isNull();
        assertThat(validations).anySatisfy(v -> assertThat(v.get("grossWeightTons").asDouble()).isEqualTo(40.0));
    }

    @Test
    void theRoutesOfALoadAreFetchedOnceAndSameProfilesShareTheValidation() {
        osrmReturns(TWO_ROUTES, ExpectedCount.once());
        restrictionsAnswer(body -> CLEAR);

        screen.annotate(LOAD, List.of(truck("a", 4.1, 15.0), truck("b", 4.1, 15.0)));
        screen.annotate(LOAD, List.of(truck("c", 4.1, 15.0)));

        server.verify();
        // Dos camiones iguales comparten la validación; la ruta principal libre basta para no mirar la alterna.
        assertThat(validations).hasSize(2);
    }

    @Test
    void busyUnverifiedOrUnmeasuredTrucksAreNotQueried() {
        restrictionsAnswer(body -> BRIDGE);
        AvailableTruckDto busy = truck("ocupado", 4.1, 15.0);
        busy.setBusyReason("Conductor ocupado con la carga #5");
        AvailableTruckDto unverified = truck("pendiente", 4.1, 15.0);
        unverified.setAccountVerified(false);
        AvailableTruckDto noHeight = truck("sin-altura", 4.1, 15.0);
        noHeight.setHeightMeters(null);

        screen.annotate(LOAD, List.of(busy, unverified, noHeight));

        assertThat(validations).isEmpty();
        assertThat(List.of(busy, unverified, noHeight)).allSatisfy(t -> assertThat(t.getRouteRestrictionReason()).isNull());
    }

    @Test
    void withoutOsrmOrRoutingRestrictionsNobodyIsDiscardedByRoute() {
        server.expect(ExpectedCount.once(), requestToUriTemplate(OSRM + "/route/v1/driving/{coords}?overview=full&geometries=geojson&alternatives=true",
                "-74.08170,4.60970;-73.36780,5.53530")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        AvailableTruckDto tall = truck("alto", 4.1, 15.0);
        screen.annotate(LOAD, List.of(tall));
        assertThat(tall.getRouteRestrictionReason()).isNull();

        setUp();
        osrmReturns(TWO_ROUTES, ExpectedCount.once());
        server.expect(ExpectedCount.manyTimes(), requestTo(RESTRICTIONS + "/api/v1/restrictions/validate-route"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        AvailableTruckDto other = truck("otro", 4.1, 15.0);
        screen.annotate(LOAD, List.of(other));
        assertThat(other.getRouteRestrictionReason()).isNull();
    }

    @Test
    void aLoadWithoutDestinationIsNotChecked() {
        AvailableTruckDto tall = truck("alto", 4.1, 15.0);
        screen.annotate(FreightLoadDto.builder().id(9L).originLatitude(4.6).originLongitude(-74.0).build(), List.of(tall));
        assertThat(tall.getRouteRestrictionReason()).isNull();
        server.verify();
    }
}
