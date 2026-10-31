package escuelaing.edu.co.truckcar.fleet_allocation.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

@Configuration
public class RestClientConfig {

    /**
     * Timeouts estrictos hacia fleets-drivers y Load-Allocation: sin ellos un vecino colgado bloqueaba el hilo del
     * consumidor de Kafka (y con él la partición) indefinidamente. El builder autoconfigurado instrumenta cada llamada
     * (span hijo, cabeceras traceparent y X-Correlation-Id, métrica http.client.requests).
     */
    @Bean
    public RestClient restClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${truckcar.http.connect-timeout:2s}") Duration connectTimeout,
            @Value("${truckcar.http.read-timeout:4s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return builder.requestFactory(requestFactory)
                .messageConverters(ignoringUnknownProperties(objectMapper))
                .build();
    }

    /**
     * Las respuestas de los vecinos traen más campos que los DTO cliente (FreightLoadDto, AvailableTruckDto). El
     * ObjectMapper de la aplicación los rechaza (FAIL_ON_UNKNOWN_PROPERTIES), así que el RestClient usa una copia que
     * los ignora: un campo nuevo en Load-Allocation o fleets-drivers no debe romper el matching.
     */
    static Consumer<List<HttpMessageConverter<?>>> ignoringUnknownProperties(ObjectMapper objectMapper) {
        ObjectMapper lenient = objectMapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return converters -> converters.replaceAll(converter -> converter instanceof MappingJackson2HttpMessageConverter
                ? new MappingJackson2HttpMessageConverter(lenient)
                : converter);
    }
}
