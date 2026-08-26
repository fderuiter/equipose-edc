package org.akaza.openclinica.exception;

/**
 * Dedicated runtime exception thrown when audit sequence retrieval fails during entity interception.
 */
public class AuditSequenceException extends OpenClinicaSystemException {

    private static final long serialVersionUID = 1L;

    public AuditSequenceException(String message) {
        super(message);
    }

    public AuditSequenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public AuditSequenceException(Throwable cause) {
        super(cause);
    }
}
