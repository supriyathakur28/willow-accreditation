package com.supriya.accreditation.platform.web;

/** A dependency is temporarily unavailable; the request is safe to retry. Answered with 503 and Retry-After. */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
