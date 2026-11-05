package escuelaing.edu.co.truckcar.fleet_allocation.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Errores del cliente (cuerpo ilegible, tipos, media type) responden 4xx con el handler real, nunca 500. */
class ClientErrorHandlingTest {

    @RestController
    static class ProbeController {
        record Probe(String name, DayOfWeek day) { }

        @PostMapping("/probe")
        String body(@RequestBody Probe probe) {
            return "ok";
        }

        @GetMapping("/probe")
        String page(@RequestParam int page) {
            return "ok";
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void malformedJsonIsABadRequestWithoutEchoingTheBody() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"clave-secreta\",\"day\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("clave-secreta"))));
    }

    @Test
    void invalidUtf8IsABadRequest() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bogotá\",\"day\":\"MONDAY\"}".getBytes(StandardCharsets.ISO_8859_1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownEnumValueIsABadRequest() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\",\"day\":\"LUNES\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongOrMissingParameterIsABadRequest() throws Exception {
        mvc.perform(get("/probe").param("page", "uno")).andExpect(status().isBadRequest());
        mvc.perform(get("/probe")).andExpect(status().isBadRequest());
    }

    @Test
    void frameworkErrorsKeepTheirStatus() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void aValidRequestStillWorks() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\",\"day\":\"MONDAY\"}"))
                .andExpect(status().isOk());
    }
}
