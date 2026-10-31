package escuelaing.edu.co.truckcar.fleet_allocation.exception;

/** El usuario no puede operar el motor de asignación: 401 sin rol, 403 si su rol no es de la torre de control. */
public class AllocationAccessDeniedException extends RuntimeException {

    private final boolean unauthenticated;

    public AllocationAccessDeniedException(String message, boolean unauthenticated) {
        super(message);
        this.unauthenticated = unauthenticated;
    }

    public boolean isUnauthenticated() {
        return unauthenticated;
    }
}
