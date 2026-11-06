package com.you.youbackend.common.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(
            @Value("${storage.endpoint}") String endpoint,
            @Value("${storage.access-key}") String accessKey,
            @Value("${storage.secret-key}") String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .build();
    }

    /**
     * 用于生成对象存储的临时上传、下载地址。
     */
    @Bean
    public S3Presigner s3Presigner(
            @Value("${storage.endpoint}") String endpoint,
            @Value("${storage.access-key}") String accessKey,
            @Value("${storage.secret-key}") String secretKey) {

        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    /**
     * 配置媒体桶的上传事件通知。
     */
    @Bean
    public ApplicationRunner configureMediaNotifications(
            S3Client s3Client,
            @Value("${storage.bucket}") String bucket) {

        return args -> s3Client.putBucketNotificationConfiguration(request -> request
                .bucket(bucket)
                .notificationConfiguration(configuration -> configuration
                        .queueConfigurations(queue -> queue
                                .id("media-upload")
                                .queueArn("arn:rustfs:sqs:us-east-1:primary:amqp")
                                .events(Event.S3_OBJECT_CREATED_PUT))));
    }
}