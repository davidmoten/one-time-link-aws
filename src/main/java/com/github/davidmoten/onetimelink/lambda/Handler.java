package com.github.davidmoten.onetimelink.lambda;

import static com.github.davidmoten.onetimelink.lambda.Util.environmentVariable;
import static com.github.davidmoten.onetimelink.lambda.Util.queueName;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.github.davidmoten.aws.helper.BadRequestException;
import com.github.davidmoten.aws.helper.ServerException;
import com.github.davidmoten.aws.helper.StandardRequestBodyPassThrough;
import com.github.davidmoten.aws.lw.client.Client;
import com.github.davidmoten.aws.lw.client.HttpMethod;
import com.github.davidmoten.aws.lw.client.xml.XmlElement;

public final class Handler implements RequestHandler<Map<String, Object>, String> {

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        StandardRequestBodyPassThrough r = StandardRequestBodyPassThrough.from(input);
        try {
            String resourcePath = r.resourcePath().get();
            String dataBucketName = environmentVariable("DATA_BUCKET_NAME");
            String applicationName = environmentVariable("WHO");
            Client s3 = Util.createS3Client();
            Client sqs = Util.createSqsClient();
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

    private static String handleStoreRequest(Map<String, Object> input, String dataBucketName,
            String applicationName, Client s3, Client sqs)
            throws InterruptedException, ExecutionException, TimeoutException {
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
            s3.path(dataBucketName + "/" + key) //
                    .method(HttpMethod.PUT) //
                    .requestBody(value.getBytes(StandardCharsets.UTF_8)) //
                    .metadata(Util.EXPIRY_TIME_EPOCH_MS, String.valueOf(expiryTime)) //
                    .execute();
            return null;
        });
        String qurl = sqs.query("Action", "CreateQueue") //
                .query("QueueName", queueName(applicationName, key)) //
                .attribute("FifoQueue", "true") //
                .attribute("ContentBasedDeduplication", "true") //
                // max retention for sqs is 14 days
                .attribute("MessageRetentionPeriod", String.valueOf(TimeUnit.DAYS.toSeconds(14))) //
                // visibility timeout can be low because only one user gets
                // the message but a higher value protects against race conditions (like
                // slowdowns on the AWS backend)
                .attribute("VisibilityTimeout", "30") //
                .responseAsXml() //
                .content("CreateQueueResult", "QueueUrl");

        sqs.url(qurl) //
                .query("Action", "SendMessage") //
                .query("MessageBody", String.valueOf(expiryTime)) //
                .query("MessageGroupId", "1") //
                .execute();
        // wait for the async action to finish
        a.get(1, TimeUnit.MINUTES);
        return "stored";
    }

    private static String handleGetRequest(StandardRequestBodyPassThrough r, String dataBucketName,
            String applicationName, Client s3, Client sqs)
            throws InterruptedException, ExecutionException, TimeoutException {
        Optional<String> k = r.queryStringParameter("key");
        if (!k.isPresent()) {
            throw new BadRequestException("key parameter not present");
        } else {
            String key = k.get();
            String queueName = queueName(applicationName, key);
            try {
                final String qurl;
                qurl = sqs //
                        .query("Action", "GetQueueUrl") //
                        .query("QueueName", queueName) //
                        .responseAsXml() //
                        .content("GetQueueUrlResult", "QueueUrl");
                List<XmlElement> list = sqs.url(qurl) //
                        .query("Action", "ReceiveMessage").responseAsXml() //
                        .child("ReceiveMessageResult") //
                        .children();
                if (list.isEmpty()) {
                    sqs.url(qurl) //
                            .query("Action", "DeleteQueue") //
                            .execute();
                    throw new GoneException("message has been read already " + key);
                } else {
                    XmlElement message = list.get(0);
                    long expiryTime = Long.parseLong(message.content("Body"));
                    sqs.url(qurl) //
                            .query("Action", "DeleteMessage") //
                            .query("ReceiptHandle", message.content("ReceiptHandle")) //
                            .execute();
                    if (expiryTime < System.currentTimeMillis()) {
                        throw new GoneException("message has expired " + key);
                    } else {
                        // perform actions in parallel
                        ExecutorService executor = Executors.newSingleThreadExecutor();
                        Future<String> result = executor.submit(() -> {
                            String answer = s3 //
                                    .path(dataBucketName + "/" + key) //
                                    .responseAsUtf8();
                            s3.path(dataBucketName + "/" + key) //
                                    .method(HttpMethod.DELETE) //
                                    .execute();
                            return answer;
                        });
                        sqs.url(qurl) //
                                .query("Action", "DeleteQueue") //
                                .execute();
                        return result.get(1, TimeUnit.MINUTES);
                    }
                }
            } catch (QueueDoesNotExistException e) {
                throw new GoneException("message has been read already (queue does not exist)");
            }
        }
    }
}
