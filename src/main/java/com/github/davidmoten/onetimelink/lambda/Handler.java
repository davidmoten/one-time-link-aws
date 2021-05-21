package com.github.davidmoten.onetimelink.lambda;

import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
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
            if ("/store".equals(resourcePath)) {
                @SuppressWarnings("unchecked")
                Map<String, String> body = (Map<String, String>) input.get("body-json");
                String key = body.get("key");
                String value = body.get("value");
                val = value;
                return "stored";
            } else if ("/get".equals(resourcePath)) {
                return val;
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
