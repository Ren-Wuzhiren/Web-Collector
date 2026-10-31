package com.webcollector.capture.security;

public class NavigationLimitException extends RuntimeException {
    public NavigationLimitException(String message) {
        super(message);
    }

    public NavigationLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
