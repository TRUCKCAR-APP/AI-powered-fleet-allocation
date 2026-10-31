package escuelaing.edu.co.truckcar.fleet_allocation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * Timeouts estrictos hacia fleets-drivers y Load-Allocation: sin ellos un vecino colgado bloqueaba el hilo del
     * consumidor de Kafka (y con él la partición) indefinidamente.
     */
    @Bean
    public RestClient restClient(
            @Value("${truckcar.http.connect-timeout:2s}") Duration connectTimeout,
            @Value("${truckcar.http.read-timeout:4s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
