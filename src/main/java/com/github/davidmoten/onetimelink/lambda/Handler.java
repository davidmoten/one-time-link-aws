package com.github.davidmoten.onetimelink.lambda;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.QueueDoesNotExistException;
import com.amazonaws.services.sqs.model.SendMessageRequest;
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
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<?> a = executor.submit(() -> s3.putObject(dataBucketName, key, value));
                Map<String, String> attributes = new HashMap<String, String>();
                attributes.put("FifoQueue", "true");
                attributes.put("ContentBasedDeduplication", "true");
                attributes.put("MessageRetentionPeriod",
                        String.valueOf(TimeUnit.DAYS.toSeconds(14))); // max is 14 days
                // visibility timeout can be low because only one user gets
                // the message but a higher value protects against race conditions (like
                // slowdowns on the AWS backend)
                attributes.put("VisibilityTimeout", "30");
                CreateQueueResult q = sqs.createQueue( //
                        new CreateQueueRequest() //
                                .withQueueName(queueName(applicationName, key)) //
                                .withAttributes(attributes));
                sqs.sendMessage( //
                        new SendMessageRequest() //
                                .withQueueUrl(q.getQueueUrl()) //
                                // needs a messageGroupId if FIFO but is irrelevant to us
                                // as only one item gets put on the queue
                                .withMessageGroupId("1") //
                                .withMessageBody(String.valueOf(expiryTime)));
                a.get(1, TimeUnit.MINUTES);
                return "stored";
            } else if ("/get".equals(resourcePath)) {
                Optional<String> k = r.queryStringParameter("key");
                if (!k.isPresent()) {
                    throw new BadRequestException("key parameter not present");
                } else {
                    String key = k.get();
                    String queueName = queueName(applicationName, key);
                    try {
                        String qurl = sqs.getQueueUrl(queueName).getQueueUrl();

                        List<Message> list = sqs.receiveMessage(qurl).getMessages();
                        if (list.isEmpty()) {
                            sqs.deleteQueue(qurl);
                            throw new GoneException("message has been read already " + key);
                        } else {
                            Message message = list.get(0);
                            long expiryTime = Long.parseLong(message.getBody());
                            // remove from queue
                            sqs.deleteMessage(queueName, message.getReceiptHandle());
                            if (expiryTime < System.currentTimeMillis()) {
                                throw new GoneException("message has expired " + key);
                            } else {
                                // perform actions in parallel
                                ExecutorService executor = Executors.newSingleThreadExecutor();
                                Future<String> result = executor.submit(() -> {
                                    String answer = s3.getObjectAsString(dataBucketName, key);
                                    s3.deleteObject(dataBucketName, key);
                                    return answer;
                                });
                                sqs.deleteQueue(qurl);
                                result.get(1, TimeUnit.MINUTES);
                                return result.get(1, TimeUnit.MINUTES);
                            }
                        }
                    } catch (QueueDoesNotExistException e) {
                        throw new GoneException(
                                "message has been read already (queue does not exist)");
                    }
                }
            } else {
                throw new BadRequestException("unknown resource path: " + resourcePath);
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e);
        } catch (BadRequestException | ServerException | GoneException e) {
            throw e;
        } catch (Throwable e) {
            throw new ServerException(e);
        }
    }

    private static String queueName(String applicationName, String key) {
        return applicationName + "-" + key + ".fifo";
    }

}
