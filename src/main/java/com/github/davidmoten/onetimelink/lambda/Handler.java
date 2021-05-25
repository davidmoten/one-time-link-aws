package com.github.davidmoten.onetimelink.lambda;

import static com.github.davidmoten.onetimelink.lambda.Util.environmentVariable;
import static com.github.davidmoten.onetimelink.lambda.Util.queueName;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.github.davidmoten.aws.helper.BadRequestException;
import com.github.davidmoten.aws.helper.ServerException;
import com.github.davidmoten.aws.helper.StandardRequestBodyPassThrough;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public final class Handler {

    public String handleRequest(Map<String, Object> input, Context context) {
        StandardRequestBodyPassThrough r = StandardRequestBodyPassThrough.from(input);
        try {
            String resourcePath = r.resourcePath().get();
            String dataBucketName = environmentVariable("DATA_BUCKET_NAME");
            String applicationName = environmentVariable("WHO");
            S3Client s3 = S3Client.create();
            SqsClient sqs = SqsClient.create();
            if ("/store".equals(resourcePath)) {
                return handleStoreRequest(input, dataBucketName, applicationName, s3, sqs);
            } else if ("/get".equals(resourcePath)) {
                return handleGetRequest(r, dataBucketName, applicationName, s3, sqs);
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

    private static String handleStoreRequest(Map<String, Object> input, String dataBucketName, String applicationName,
            S3Client s3, SqsClient sqs) throws InterruptedException, ExecutionException, TimeoutException {
        final String key;
        final String value;
        final long expiryDurationMs;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) input.get("body-json");
            key = body.get("key");
            value = body.get("value");
            expiryDurationMs = Long.parseLong(body.get("expiryDurationMs"));
        } catch (Throwable e) {
            throw new BadRequestException(e);
        }

        long expiryTime = System.currentTimeMillis() + expiryDurationMs;
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<?> a = executor.submit(() -> {
            Map<String, String> metadata = new HashMap<>();
            metadata.put(Util.EXPIRY_TIME_EPOCH_MS, String.valueOf(expiryTime));
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            PutObjectRequest request = PutObjectRequest //
                    .builder() //
                    .bucket(dataBucketName) //
                    .key(key) //
                    .metadata(metadata) //
                    .build();
            s3.putObject(request, RequestBody.fromBytes(bytes));
            return null;
        });
        Map<QueueAttributeName, String> attributes = new HashMap<>();
        attributes.put(QueueAttributeName.FIFO_QUEUE, "true");
        attributes.put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true");

        // max retention for sqs is 14 days
        attributes.put(QueueAttributeName.MESSAGE_RETENTION_PERIOD, String.valueOf(TimeUnit.DAYS.toSeconds(14)));

        // visibility timeout can be low because only one user gets
        // the message but a higher value protects against race conditions (like
        // slowdowns on the AWS backend)
        attributes.put(QueueAttributeName.VISIBILITY_TIMEOUT, "30");
        CreateQueueResponse q = sqs.createQueue( //
                CreateQueueRequest //
                        .builder() //
                        .queueName(queueName(applicationName, key)) //
                        .attributes(attributes) //
                        .build());

        sqs.sendMessage( //
                SendMessageRequest.builder() //
                        .queueUrl(q.queueUrl()) //
                        // needs a messageGroupId if FIFO but is irrelevant to us
                        // as only one item gets put on the queue
                        .messageGroupId("1") //
                        .messageBody(String.valueOf(expiryTime)) //
                        .build());
        a.get(1, TimeUnit.MINUTES);
        return "stored";
    }

    private static String handleGetRequest(StandardRequestBodyPassThrough r, String dataBucketName,
            String applicationName, S3Client s3, SqsClient sqs)
            throws InterruptedException, ExecutionException, TimeoutException {
        Optional<String> k = r.queryStringParameter("key");
        if (!k.isPresent()) {
            throw new BadRequestException("key parameter not present");
        } else {
            String key = k.get();
            String queueName = queueName(applicationName, key);
            try {
                String qurl = sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();

                List<Message> list = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(qurl).build())
                        .messages();
                if (list.isEmpty()) {
                    sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(qurl).build());
                    throw new GoneException("message has been read already " + key);
                } else {
                    Message message = list.get(0);
                    long expiryTime = Long.parseLong(message.body());
                    // remove from queue
                    sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(qurl)
                            .receiptHandle(message.receiptHandle()).build());
                    if (expiryTime < System.currentTimeMillis()) {
                        throw new GoneException("message has expired " + key);
                    } else {
                        // perform actions in parallel
                        ExecutorService executor = Executors.newSingleThreadExecutor();
                        Future<String> result = executor.submit(() -> {
                            String answer = s3.getObjectAsBytes(GetObjectRequest //
                                    .builder() //
                                    .bucket(dataBucketName) //
                                    .key(key) //
                                    .build()) //
                                    .asString(StandardCharsets.UTF_8);
                            s3.deleteObject(DeleteObjectRequest.builder().bucket(dataBucketName).key(key).build());
                            return answer;
                        });
                        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(qurl).build());
                        return result.get(1, TimeUnit.MINUTES);
                    }
                }
            } catch (QueueDoesNotExistException e) {
                throw new GoneException("message has been read already (queue does not exist)");
            }
        }
    }
}
