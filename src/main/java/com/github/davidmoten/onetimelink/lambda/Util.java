package com.github.davidmoten.onetimelink.lambda;

final class Util {
    static final String EXPIRY_TIME_EPOCH_MS = "expiryTimeEpochMs";

    static String queueName(String applicationName, String key) {
        return applicationName + "-" + key + ".fifo";
    }

}
