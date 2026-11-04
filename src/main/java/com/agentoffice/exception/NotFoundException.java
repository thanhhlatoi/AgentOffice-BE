package com.agentoffice.exception;

public class NotFoundException extends AppException {
    public NotFoundException(String what, Object id) {
        super(ErrorCode.NOT_FOUND, what + " not found: " + id);
    }
}
