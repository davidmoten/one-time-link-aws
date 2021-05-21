package com.github.davidmoten.onetimelink.lambda;

import java.util.Map;
import java.util.Optional;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.github.davidmoten.aws.helper.BadRequestException;
import com.github.davidmoten.aws.helper.ServerException;
import com.github.davidmoten.aws.helper.StandardRequestBodyPassThrough;

public final class Handler implements RequestHandler<Map<String, Object>, String> {

    private static String val = "not set";

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        StandardRequestBodyPassThrough r = StandardRequestBodyPassThrough.from(input);
        try {
            String resourcePath = r.resourcePath().get();
            String dataBucketName = System.getenv("DATA_BUCKET_NAME");
            if (dataBucketName == null) {
                throw new ServerException("environment variable DATA_BUCKET_NAME not set");
            }
            AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
            if ("/store".equals(resourcePath)) {
                @SuppressWarnings("unchecked")
                Map<String, String> body = (Map<String, String>) input.get("body-json");
                String key = body.get("key");
                String value = body.get("value");
                s3.putObject(dataBucketName, key, value);
                return "stored";
            } else if ("/get".equals(resourcePath)) {
                Optional<String> key = r.queryStringParameter("key");
                if (!key.isPresent()) {
                    throw new BadRequestException("key parameter not present");
                } else {
                    String result =  s3.getObjectAsString(dataBucketName, key.get());
                    s3.deleteObject(dataBucketName, key.get());
                    return result;
                }
            } else {
                throw new BadRequestException("unknown resource path: " + resourcePath);
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e);
        } catch (BadRequestException | ServerException e) {
            throw e;
        } catch (Throwable e) {
            throw new ServerException(e);
        }
    }

}
