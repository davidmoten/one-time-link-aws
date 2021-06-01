package com.github.davidmoten.onetimelink.lambda;

import static com.github.davidmoten.onetimelink.lambda.Util.environmentVariable;
import static com.github.davidmoten.onetimelink.lambda.Util.queueName;

import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.iterable.S3Objects;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.QueueDoesNotExistException;
import com.github.davidmoten.aws.lw.client.Client;
import com.github.davidmoten.aws.lw.client.HttpMethod;
import com.github.davidmoten.aws.lw.client.Response;

public final class CleanupHandler {

    public String handle(Map<String, Object> input, Context context) {
        String dataBucketName = environmentVariable("DATA_BUCKET_NAME");
        String applicationName = environmentVariable("WHO");
        if (true) {
            Client s3 = Client.s3().defaultClient();
            Client sqs = Client.sqs().defaultClient();
            long count = s3 //
                    .url("https://" + dataBucketName + ".s3." + s3.regionName() + ".amazonaws.com") //
                    .query("list-type", "2") //
                    .responseAsXml() //
                    .childrenWithName("Contents") //
                    .stream() //
                    .map(x -> x.content("Key")) //
                    .filter(key -> {
                        Response r = s3 //
                                .path(dataBucketName + "/" + key) //
                                .method(HttpMethod.HEAD) //
                                .response();
                        Optional<String> s = r.metadataFirst(Util.EXPIRY_TIME_EPOCH_MS);
                        return s.isPresent()
                                && Long.parseLong(s.get()) < System.currentTimeMillis();
                    }) //
                    .peek(key -> {
                        String queueName = queueName(applicationName, key);
                        String queueUrl = sqs //
                                .query("Action", "GetQueueUrl") //
                                .query("QueueName", queueName) //
                                .responseAsXml() //
                                .content("GetQueueUrlResult", "QueueUrl");
                        try {
                            sqs.url(queueUrl) //
                                    .query("Action", "DeleteQueue") //
                                    .execute();
                        } catch (Throwable e) {
                            // TODO tighten catch to detect queue does not exist
                            // ignore
                        }
                        s3.path(dataBucketName + "/" + key) //
                                .method(HttpMethod.DELETE) //
                                .execute();
                    }).count();
            return count + " s3 objects deleted with their associated queues";
        } else {
            AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
            AmazonSQS sqs = AmazonSQSClientBuilder.defaultClient();
            long count = StreamSupport
                    .stream(S3Objects.inBucket(s3, dataBucketName).spliterator(), false) //
                    .filter(x -> {
                        String s = (String) s3.getObjectMetadata(dataBucketName, x.getKey())
                                .getUserMetaDataOf(Util.EXPIRY_TIME_EPOCH_MS);
                        return s != null && Long.parseLong(s) < System.currentTimeMillis();
                    }) //
                    .peek(x -> {
                        String key = x.getKey();
                        String queueName = queueName(applicationName, key);
                        try {
                            String qurl = sqs.getQueueUrl(queueName).getQueueUrl();
                            sqs.deleteQueue(qurl);
                        } catch (QueueDoesNotExistException e) {
                            // ignore
                        }
                        s3.deleteObject(dataBucketName, key);
                    }) //
                    .count();
            return count + " s3 objects deleted with their associated queues";
        }
    }

}
