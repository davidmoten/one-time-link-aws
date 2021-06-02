package com.github.davidmoten.onetimelink.lambda;

public class QueueDoesNotExistException extends RuntimeException {

    private static final long serialVersionUID = -1451928577050014071L;

    public QueueDoesNotExistException(String message) {
        super(message);
    }

}
