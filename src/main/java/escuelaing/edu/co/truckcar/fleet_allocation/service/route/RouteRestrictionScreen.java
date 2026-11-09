package escuelaing.edu.co.truckcar.fleet_allocation.service.route;

import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.AvailableTruckDto;
import escuelaing.edu.co.truckcar.fleet_allocation.dto.client.FreightLoadDto;

import java.util.List;

/**
 * Filtro de gálibo y peso sobre la ruta de la carga: marca en cada camión (routeRestrictionReason) si ninguna ruta entre
 * el origen y el destino le sirve, para que el motor lo descarte con ese motivo. Si no se puede verificar (OSRM o
 * Routing-Restrictions no responden) no marca nada: la navegación del conductor vuelve a validar la ruta y avisa.
 */
public interface RouteRestrictionScreen {

    void annotate(FreightLoadDto load, List<AvailableTruckDto> trucks);

    /** Sin filtro de ruta (pruebas del resto del motor y configuraciones sin Routing-Restrictions). */
    static RouteRestrictionScreen disabled() {
        return (load, trucks) -> { };
    }
}
