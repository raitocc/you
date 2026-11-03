package com.you.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

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
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .forcePathStyle(true)
        .build();
  }

  @Bean
  public software.amazon.awssdk.services.s3.presigner.S3Presigner presigner(
      @Value("${storage.public-endpoint:${storage.endpoint}}") String endpoint,
      @Value("${storage.access-key}") String accessKey,
      @Value("${storage.secret-key}") String secretKey) {
    return software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .serviceConfiguration(
            software.amazon.awssdk.services.s3.S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .checksumValidationEnabled(false)
                .build())
        .build();
  }
}
