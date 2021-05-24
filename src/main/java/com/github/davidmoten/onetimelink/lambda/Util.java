package com.github.davidmoten.onetimelink.lambda;

import com.github.davidmoten.aws.helper.ServerException;

final class Util {
    static final String EXPIRY_TIME_EPOCH_MS = "expiryTimeEpochMs";

    private Util() {
        // prevent instantiation
    }
    
    static String queueName(String applicationName, String key) {
        return applicationName + "-" + key + ".fifo";
    }
    
    static String environmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new ServerException(
                    "environment variable "+ name + " (the application name) not set");
        } else {
            return value;
        }
    }
}
