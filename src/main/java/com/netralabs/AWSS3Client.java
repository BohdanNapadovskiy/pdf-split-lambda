package com.netralabs;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

public class AWSS3Client {

  AmazonS3 s3Client = buildS3Client();
  private static final Logger logger = LoggerFactory.getLogger(AWSS3Client.class);

  private static AmazonS3 buildS3Client() {
    String region = System.getProperty("aws.region");
    if (region == null || region.isEmpty()) {
      region = System.getProperty("AWS_REGION");
    }
    if (region == null || region.isEmpty()) {
      region = System.getenv("AWS_REGION");
    }
    AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
    if (region != null && !region.isEmpty()) {
      builder = builder.withRegion(region);
    }
    return builder.build();
  }

  public S3Object getObject(String bucketName, String folder, String fileName) throws FileNotFoundException {
    String fileKey = buildKey(folder, fileName);
    try {
      logger.info("Fetching object from S3: {}/{}", bucketName, fileKey);
      return s3Client.getObject(bucketName, fileKey);
    } catch (com.amazonaws.services.s3.model.AmazonS3Exception s3e) {
      if (s3e.getStatusCode() == 404 && "NoSuchKey".equals(s3e.getErrorCode())) {
        logger.error("File not found in S3. Key does not exist: {}", fileKey);
        throw new FileNotFoundException("The specified key does not exist in S3: " + fileKey);
      }
      throw s3e;
    }
  }

  public String buildKey(String folder, String fileName) {
    return (folder == null || folder.isEmpty()) ? fileName : folder + "/" + fileName;
  }

  public void uploadFile(String bucketName, String folder, String fileName, File file) {
    String key = buildKey(folder, fileName);
    logger.info("Uploading file to S3: {}/{}", bucketName, key);
    PutObjectRequest request = new PutObjectRequest(bucketName, key, file);
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentType("application/pdf");
    request.setMetadata(metadata);
    s3Client.putObject(request);
  }

  public void PutObjectToBucket(String bucketName, String folder, String fileName, String fileContent) {
    // Keep for backward compatibility: upload provided content as a new object
    String key = buildKey(folder, fileName);
    byte[] bytes = fileContent != null ? fileContent.getBytes() : new byte[0];
    ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
    ObjectMetadata meta = new ObjectMetadata();
    meta.setContentLength(bytes.length);
    s3Client.putObject(bucketName, key, bais, meta);
  }

}
