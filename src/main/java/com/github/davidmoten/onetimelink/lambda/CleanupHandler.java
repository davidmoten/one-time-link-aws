package com.github.davidmoten.onetimelink.lambda;

import static com.github.davidmoten.onetimelink.lambda.Util.environmentVariable;

import java.util.Map;
import java.util.stream.StreamSupport;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

public final class CleanupHandler {

    public String handle(Map<String, Object> input, Context context) {
        String dataBucketName = environmentVariable("DATA_BUCKET_NAME");
        String applicationName = environmentVariable("WHO");
        S3Client s3 = S3Client.create();
        SqsClient sqs = SqsClient.create();
        
        ListObjectsV2Request request =
                ListObjectsV2Request
                        .builder()
                        .bucket(dataBucketName) //
                        .prefix("")
                        .build();
        ListObjectsV2Iterable response = s3.listObjectsV2Paginator(request);
        
        long count = StreamSupport
                .stream(response.contents().spliterator(), false) //
                .filter(x -> {
                    HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket( dataBucketName).key(x.key()).build());
                    if (!head.hasMetadata()) {
                        return false;
                    } else {
                    String s = head.metadata().get(Util.EXPIRY_TIME_EPOCH_MS);
                    return s != null && Long.parseLong(s) < System.currentTimeMillis();
                    }
                }) //
                .peek(x -> {
                    String key = x.key();
                    String queueName = Util.queueName(applicationName, key);
                    try {
                        String qurl = sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
                        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(qurl).build());
                    } catch (QueueDoesNotExistException e) {
                        // ignore
                    }
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(dataBucketName).key(key).build());
                }) //
                .count();
        return count + " s3 objects deleted with their associated queues";
    }

}
