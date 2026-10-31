package escuelaing.edu.co.truckcar.fleet_allocation.config;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestClientConfigTest {

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
