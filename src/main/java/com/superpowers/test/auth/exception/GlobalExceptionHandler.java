package com.superpowers.test.auth.exception;

import com.superpowers.test.onboarding.exception.InvalidFileException;
import com.superpowers.test.storage.FileStorageException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed.");
        problem.setTitle("Validation Failed");
        problem.setProperty("errorCode", "VALIDATION_FAILED");
        problem.setProperty("message", "Request validation failed.");
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Email Already Exists");
        problem.setProperty("errorCode", "EMAIL_ALREADY_EXISTS");
        problem.setProperty("message", ex.getMessage());
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

    @ExceptionHandler(InvalidFileException.class)
    public ProblemDetail handleInvalidFile(InvalidFileException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid File");
        problem.setProperty("errorCode", "INVALID_FILE");
        problem.setProperty("message", ex.getMessage());
        return problem;
    }

    @ExceptionHandler(FileStorageException.class)
    public ProblemDetail handleFileStorageFailure(FileStorageException ex) {
        log.error("Onboarding file storage failure", ex);
        String detail = "Unable to complete onboarding due to an internal error.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, detail);
        problem.setTitle("Onboarding Failed");
        problem.setProperty("errorCode", "ONBOARDING_FAILED");
        problem.setProperty("message", detail);
        return problem;
    }

    public record FieldViolation(String field, String message) {
    }
}
