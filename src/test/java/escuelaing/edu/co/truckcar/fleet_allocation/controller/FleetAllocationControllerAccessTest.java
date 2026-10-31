package escuelaing.edu.co.truckcar.fleet_allocation.controller;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.response.AllocationResultResponse;
import escuelaing.edu.co.truckcar.fleet_allocation.exception.GlobalExceptionHandler;
import escuelaing.edu.co.truckcar.fleet_allocation.model.AllocationStatus;
import escuelaing.edu.co.truckcar.fleet_allocation.service.IFleetAllocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El matching manual y el historial de decisiones son solo para la torre de control. */
class FleetAllocationControllerAccessTest {

    private IFleetAllocationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(IFleetAllocationService.class);
        when(service.processAutomaticAllocationForLoad(anyLong()))
                .thenReturn(AllocationResultResponse.builder().status(AllocationStatus.SUCCESSFUL).build());
        when(service.getDecisionsByLoad(anyLong())).thenReturn(List.of());
        mvc = MockMvcBuilders.standaloneSetup(new FleetAllocationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COORDINADOR", "ADMIN"})
    void controlTowerRunsManualMatching(String role) throws Exception {
        mvc.perform(post("/api/v1/fleet-allocation/match/7").header("X-Auth-Role", role)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/fleet-allocation/history/7").header("X-Auth-Role", role)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONDUCTOR", "GENERADOR"})
    void otherRolesGetForbidden(String role) throws Exception {
        mvc.perform(post("/api/v1/fleet-allocation/match/7").header("X-Auth-Role", role)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fleet-allocation/history/7").header("X-Auth-Role", role)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void missingRoleIsUnauthenticated() throws Exception {
        mvc.perform(post("/api/v1/fleet-allocation/match/7")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
