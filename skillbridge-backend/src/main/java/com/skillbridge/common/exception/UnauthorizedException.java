package com.skillbridge.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Authentication is missing, malformed, expired, or the credentials presented
 * are wrong.
 *
 * <p>Keep the message vague on the login path. "Invalid email or password" is
 * correct; "no account with that email" is a user-enumeration oracle that lets
 * anyone test whether an address is registered.
 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }
}
