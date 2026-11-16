package escuelaing.edu.co.truckcar.fleet_allocation.exception;

/** La carga pedida en el matching manual no existe en Load-Allocation: 404. */
public class LoadNotFoundException extends RuntimeException {

    public LoadNotFoundException(Long loadId) {
        super("No existe la carga con id " + loadId);
    }
}
