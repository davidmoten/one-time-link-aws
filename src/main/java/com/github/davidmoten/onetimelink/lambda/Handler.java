package com.github.davidmoten.onetimelink.lambda;

import static com.github.davidmoten.onetimelink.lambda.Util.environmentVariable;
import static com.github.davidmoten.onetimelink.lambda.Util.queueName;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.QueueAttributeName;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.util.StringInputStream;
import com.github.davidmoten.aws.helper.BadRequestException;
import com.github.davidmoten.aws.helper.ServerException;
import com.github.davidmoten.aws.helper.StandardRequestBodyPassThrough;

public final class Handler implements RequestHandler<Map<String, Object>, String> {

    private static final AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
    private static final AmazonSQS sqs = AmazonSQSClientBuilder.defaultClient();

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        StandardRequestBodyPassThrough r = StandardRequestBodyPassThrough.from(input);
        try {
            String resourcePath = r.resourcePath().get();
            String dataBucketName = environmentVariable("DATA_BUCKET_NAME");
            String applicationName = environmentVariable("WHO");

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
            AmazonS3 s3, AmazonSQS sqs) throws InterruptedException, ExecutionException, TimeoutException {
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
            putObject(s3, dataBucketName, key, value, expiryTime);
            return null;
        });
        String qurl = createFifoQueue(sqs, applicationName, key);

        sendMessage(sqs, expiryTime, qurl);

        // wait for the async action to finish
        a.get(1, TimeUnit.MINUTES);
        return "stored";
    }

    private static void putObject(AmazonS3 s3, String dataBucketName, final String key, final String value,
            long expiryTime) {
//        s3.path(dataBucketName + "/" + key) //
//                .method(HttpMethod.PUT) //
//                .requestBody(value.getBytes(StandardCharsets.UTF_8)) //
//                .metadata(Util.EXPIRY_TIME_EPOCH_MS, String.valueOf(expiryTime)) //
//                .execute();
//        s3.putObject(PutObjectRequest.builder() //
//                .bucket(dataBucketName) //
//                .key(key) //
//                .metadata(Collections.singletonMap(Util.EXPIRY_TIME_EPOCH_MS, String.valueOf(expiryTime))).build(),
//                RequestBody.fromBytes(value.getBytes(StandardCharsets.UTF_8)));
        ObjectMetadata m = new ObjectMetadata();
        m.addUserMetadata(Util.EXPIRY_TIME_EPOCH_MS, String.valueOf(expiryTime));
        try {
            s3.putObject(new PutObjectRequest(dataBucketName, key, new StringInputStream(value), m));
        } catch (UnsupportedEncodingException e) {
            throw new ServerException(value);
        }
    }

    private static String createFifoQueue(AmazonSQS sqs, String applicationName, final String key) {
//        String qurl = sqs.query("Action", "CreateQueue") //
//                .query("QueueName", queueName(applicationName, key)) //
//                .attribute("FifoQueue", "true") //
//                .attribute("ContentBasedDeduplication", "true") //
//                // max retention for sqs is 14 days
//                .attribute("MessageRetentionPeriod", String.valueOf(TimeUnit.DAYS.toSeconds(14))) //
//                // visibility timeout can be low because only one user gets
//                // the message but a higher value protects against race conditions (like
//                // slowdowns on the AWS backend)
//                .attribute("VisibilityTimeout", "30") //
//                .responseAsXml() //
//                .content("CreateQueueResult", "QueueUrl");
        Map<String, String> attributes = new HashMap<>();
        attributes.put(QueueAttributeName.FIFO_QUEUE, "true");
        attributes.put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true");
        // max retention for sqs is 14 days
        attributes.put(QueueAttributeName.MESSAGE_RETENTION_PERIOD, String.valueOf(TimeUnit.DAYS.toSeconds(14)));
        // visibility timeout can be low because only one user gets
        // the message but a higher value protects against race conditions (like
        // slowdowns on the AWS backend)
        attributes.put(QueueAttributeName.VISIBILITY_TIMEOUT, "30");
        CreateQueueResponse r = sqs.createQueue(
                new CreateQueueRequest(queueName(applicationName, key)).withAttributes(attributes));
        return r.queueUrl();
    }

    private static void sendMessage(AmazonSQS sqs, long expiryTime, String qurl) {
//        sqs.url(qurl) //
//                .query("Action", "SendMessage") //
//                .query("MessageBody", String.valueOf(expiryTime)) //
//                .query("MessageGroupId", "1") //
//                .execute();
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(qurl).messageBody(String.valueOf(expiryTime))
                .messageGroupId("1").build());
    }

    private static String handleGetRequest(StandardRequestBodyPassThrough r, String dataBucketName,
            String applicationName, AmazonS3 s3, AmazonSQS sqs)
            throws InterruptedException, ExecutionException, TimeoutException {
        return "";
    }
}
