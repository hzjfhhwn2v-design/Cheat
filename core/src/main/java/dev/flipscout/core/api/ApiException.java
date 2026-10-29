package dev.flipscout.core.api;

import java.io.IOException;

/** A source answered, but with an error (HTTP status or success:false). */
public class ApiException extends IOException {
    private static final long serialVersionUID = 1L;

    public final int status;
    public final long retryAfterMs;

    public ApiException(String message, int status, long retryAfterMs) {
        super(message);
        this.status = status;
        this.retryAfterMs = retryAfterMs;
    }
}
