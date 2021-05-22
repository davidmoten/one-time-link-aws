package com.github.davidmoten.onetimelink.lambda;

import static com.github.davidmoten.onetimelink.lambda.Util.queueName;

import java.util.Map;
import java.util.stream.StreamSupport;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.iterable.S3Objects;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.QueueDoesNotExistException;
import com.github.davidmoten.aws.helper.ServerException;

public final class CleanupHandler {

    public String handle(Map<String, Object> input, Context context) {
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
