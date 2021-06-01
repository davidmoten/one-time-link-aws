package com.github.davidmoten.onetimelink.lambda;

import static com.github.davidmoten.onetimelink.lambda.Util.environmentVariable;
import static com.github.davidmoten.onetimelink.lambda.Util.queueName;

import java.util.Map;
import java.util.Optional;

import com.amazonaws.services.lambda.runtime.Context;
import com.github.davidmoten.aws.lw.client.Client;
import com.github.davidmoten.aws.lw.client.HttpMethod;
import com.github.davidmoten.aws.lw.client.Response;
import com.github.davidmoten.aws.lw.client.ServiceException;

public final class CleanupHandler {

    public String handle(Map<String, Object> input, Context context) {
        String dataBucketName = environmentVariable("DATA_BUCKET_NAME");
        String applicationName = environmentVariable("WHO");
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
                    Optional<String> s = r.metadata(Util.EXPIRY_TIME_EPOCH_MS);
                    return s.isPresent() && Long.parseLong(s.get()) < System.currentTimeMillis();
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
                    } catch (ServiceException e) {
                        if (!e.getMessage().contains(AwsConstants.NON_EXISTENT_QUEUE)) {
                            throw e;
                        }
                    }
                    s3.path(dataBucketName + "/" + key) //
                            .method(HttpMethod.DELETE) //
                            .execute();
                }).count();
        return count + " s3 objects deleted with their associated queues";
    }

}
