package com.github.davidmoten.onetimelink.lambda;

import com.github.davidmoten.aws.helper.ServerException;

final class Util {
    // use all lower case, letters and digits only
    static final String EXPIRY_TIME_EPOCH_MS = "expirytimeepochms";

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
                    "environment variable " + name + " (the application name) not set");
        } else {
            return value;
        }
    }

//    static Client createS3Client() {
//        return Client.s3().defaultClient().build();
//    }
//
//    static Client createSqsClient() {
//        return Client //
//                .sqs() //
//                .defaultClient() //
//                .exception( //
//                        x -> !x.isOk() && x.contentUtf8().contains(AwsConstants.NON_EXISTENT_QUEUE), //
//                        x -> new QueueDoesNotExistException(x.contentUtf8())) //
//                .build();
//    }
}
