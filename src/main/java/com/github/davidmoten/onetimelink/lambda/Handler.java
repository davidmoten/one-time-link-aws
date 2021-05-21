package com.github.davidmoten.onetimelink.lambda;

import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Builder;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.github.davidmoten.aws.helper.BadRequestException;
import com.github.davidmoten.aws.helper.ServerException;

public final class Handler implements RequestHandler<Map<String, Object>, String> {

    private static String val = "not set";

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) input.get("context");
            String resourcePath = (String) c.get("resource-path");
            String dataBucketName = System.getenv("DATA_BUCKET_NAME");
            if (dataBucketName == null) {
                throw new ServerException("environment variable DATA_BUCKET_NAME not set");
            }
            AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) input.get("body-json");
            String key = body.get("key");
            if ("/store".equals(resourcePath)) {
                String value = body.get("value");
                s3.putObject(dataBucketName, key, value);
                return "stored";
            } else if ("/get".equals(resourcePath)) {
                return s3.getObjectAsString(dataBucketName, key);
            } else {
                throw new BadRequestException("unknown resource path: " + resourcePath);
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e);
        } catch (Throwable e) {
            throw new ServerException(e);
        }
    }

}
