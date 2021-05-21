package com.github.davidmoten.onetimelink.lambda;

public class GoneException extends RuntimeException {

    private static final long serialVersionUID = -4721077082534091906L;

    public GoneException(String message) {
        this(message, null);
    }

    public GoneException(String message, Throwable e) {
        super("Gone: " + message, e);
    }

}
