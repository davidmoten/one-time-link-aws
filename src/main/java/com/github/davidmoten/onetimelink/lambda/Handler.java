package com.github.davidmoten.onetimelink.lambda;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.Message;
import com.github.davidmoten.aws.helper.BadRequestException;
import com.github.davidmoten.aws.helper.ServerException;
import com.github.davidmoten.aws.helper.StandardRequestBodyPassThrough;

public final class Handler implements RequestHandler<Map<String, Object>, String> {

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        StandardRequestBodyPassThrough r = StandardRequestBodyPassThrough.from(input);
        try {
            String resourcePath = r.resourcePath().get();
            String dataBucketName = System.getenv("DATA_BUCKET_NAME");
            if (dataBucketName == null) {
                throw new ServerException("environment variable DATA_BUCKET_NAME not set");
            }
            String applicationName = System.getenv("WHO");
            if (applicationName == null) {
                throw new ServerException("environment variable WHO not set");
            }
            AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
            AmazonSQS sqs = AmazonSQSClientBuilder.defaultClient();
            if ("/store".equals(resourcePath)) {
                @SuppressWarnings("unchecked")
                Map<String, String> body = (Map<String, String>) input.get("body-json");
                String key = body.get("key");
                String value = body.get("value");
                long expiryDurationMs = Long.parseLong(body.get("expiryDurationMs"));
                long expiryTime = System.currentTimeMillis() + expiryDurationMs;
                s3.putObject(dataBucketName, key, value);
                Map<String, String> attributes = new HashMap<String, String>();
                attributes.put("FifoQueue", "true");
                CreateQueueResult q = sqs.createQueue( //
                        new CreateQueueRequest() //
                                .withQueueName(queueName(applicationName, key)) //
                                .withAttributes(attributes));
                sqs.sendMessage(q.getQueueUrl(), String.valueOf(expiryTime));
                return "stored";
            } else if ("/get".equals(resourcePath)) {
                Optional<String> k = r.queryStringParameter("key");
                if (!k.isPresent()) {
                    throw new BadRequestException("key parameter not present");
                } else {
                    String key = k.get();
                    String queueName = queueName(applicationName, key);
                    String qurl = sqs.getQueueUrl(queueName).getQueueUrl();
                    List<Message> list = sqs.receiveMessage(qurl).getMessages();
                    if (list.isEmpty()) {
                        sqs.deleteQueue(qurl);
                        throw new RuntimeException("NotFound: no message found for " + key);
                    }
                    String result = s3.getObjectAsString(dataBucketName, key);
                    s3.deleteObject(dataBucketName, key);
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

    private static String queueName(String applicationName, String key) {
        return applicationName + "-" + key;
    }

}
