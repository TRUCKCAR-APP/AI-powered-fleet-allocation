package escuelaing.edu.co.truckcar.fleet_allocation.config;

import com.sun.net.httpserver.HttpServer;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestClientConfigTest {

    /**
     * Regresión: con HttpURLConnection el PATCH /assign fallaba ("Invalid HTTP method: PATCH") y ninguna propuesta
     * automática se confirmaba en Load-Allocation. Se prueba contra un servidor HTTP real, no contra un mock.
     */
    @Test
    void confirmationPatchReachesTheNeighbor() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> method = new AtomicReference<>();
        server.createContext("/api/v1/loads/7/assign", exchange -> {
            method.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            RestClient client = new RestClientConfig().restClient(RestClient.builder(), new JacksonConfig().objectMapper(),
                    Duration.ofSeconds(2), Duration.ofSeconds(4));

            client.patch()
                    .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/loads/7/assign")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"driverId\":\"d-1\"}")
                    .retrieve()
                    .toBodilessEntity();

            assertThat(method.get()).isEqualTo("PATCH");
        } finally {
            server.stop(0);
        }
    }

    /** Regresión: con el ObjectMapper estricto de la app, un campo que el DTO no conoce hacía fallar el matching. */
    @Test
    void neighborResponsesWithFieldsUnknownToTheClientDtoAreStillMapped() {
        RestClient.Builder builder = RestClient.builder()
                .messageConverters(RestClientConfig.ignoringUnknownProperties(new JacksonConfig().objectMapper()));
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://load-allocation:8083/api/v1/loads/7"))
                .andRespond(withSuccess("{\"id\":7,\"cargoDescription\":\"Café\",\"weightTons\":12.5,"
                        + "\"podReceiverName\":\"campo que FreightLoadDto no declara\"}", MediaType.APPLICATION_JSON));

        FreightLoadDto load = builder.build().get()
                .uri("http://load-allocation:8083/api/v1/loads/7")
                .retrieve()
                .body(FreightLoadDto.class);

        server.verify();
        assertThat(load).isNotNull();
        assertThat(load.getId()).isEqualTo(7L);
        assertThat(load.getWeightTons()).isEqualTo(12.5);
    }
}
