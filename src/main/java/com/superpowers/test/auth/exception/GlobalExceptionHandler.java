package com.superpowers.test.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed.");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation Failed");
        problem.setProperty("errorCode", "VALIDATION_FAILED");
        problem.setProperty("message", detail);
        return problem;
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ProblemDetail handleAuthFailed(AuthenticationFailedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Authentication Failed");
        problem.setProperty("errorCode", "AUTH_FAILED");
        problem.setProperty("message", ex.getMessage());
        return problem;
    }

    @ExceptionHandler(AccountLockedException.class)
    public ProblemDetail handleAccountLocked(AccountLockedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.LOCKED, ex.getMessage());
        problem.setTitle("Account Locked");
        problem.setProperty("errorCode", "ACCOUNT_LOCKED");
        problem.setProperty("message", ex.getMessage());
        problem.setProperty("lockedUntil", ex.getLockedUntil().toString());
        return problem;
    }
}
