package com.github.davidmoten.oas3.puml.api;

import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

public final class Handler implements RequestHandler<Map<String, Object>, String> {

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            return "{\"response\": \"hi there!\"}";
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("BadRequest: " + e.getMessage(), e);
        } catch (Throwable e) {
            throw new RuntimeException("ServerException: " + e.getMessage(), e);
        }
    }

}
