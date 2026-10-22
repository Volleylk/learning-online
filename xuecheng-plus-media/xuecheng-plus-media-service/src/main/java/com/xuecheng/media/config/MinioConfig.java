package com.xuecheng.media.config;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @description minio配置
 * @author Mr.M
 * @date 2022/9/12 19:32
 * @version 1.0
 */
@Configuration
public class MinioConfig {


    @Value("${minio.endpoint}")
    private String endpoint;
    @Value("${minio.accessKey}")
    private String accessKey;
    @Value("${minio.secretKey}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {

        MinioClient minioClient =
                MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(accessKey, secretKey)
                        .build();
        return minioClient;
    }

    /**
     * AWS S3 客户端：MinIO 兼容 S3 API，Multipart Upload 标准协议由 AWS SDK 完成
     * （minio-java 8.4+ 移除了公开 multipart API，故 multipart 链路走 AWS SDK）
     */
    @Bean
    public AmazonS3 amazonS3() {
        ClientConfiguration config = new ClientConfiguration();
        // MinIO 要求 AWS SigV4 签名
        config.setSignerOverride("AWSS3V4SignerType");
        return AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, "us-east-1"))
                .withClientConfiguration(config)
                // MinIO 不支持 virtual-host 风格，必须 path-style
                .withPathStyleAccessEnabled(true)
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
                .build();
    }
}