package com.webcollector.capture.security;

/** URL 不安全或无法通过 SSRF 策略时抛出的运行时异常。 */
public class UnsafeUrlException extends IllegalArgumentException {
    public UnsafeUrlException(String message) {
        super(message);
    }

    public UnsafeUrlException(String message, Throwable cause) {
        super(message, cause);
    }
}
