package com.netralabs;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.model.S3Object;
import com.netralabs.domain.EventRequest;
import com.netralabs.domain.SplitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PdfSplitter implements RequestHandler<Map<String, String>, Map<String, Object>> {

  private static final Logger logger = LoggerFactory.getLogger(PdfSplitter.class);
  private final TaggedPdfSplitter taggedPdfSplitter = new TaggedPdfSplitter();
  private final AWSS3Client s3Client = new AWSS3Client();

  @Override
  public Map<String, Object> handleRequest(Map<String, String> input, Context context) {
    EventRequest eventRequest = new EventRequest(input);
    logger.info("Event request: splitRange={}, bucket={}, folder={}, file={}", eventRequest.getSplitRange(), eventRequest.getBucketName(), eventRequest.getFolderName(), eventRequest.getFileName());

    Map<String, Object> response = new HashMap<>();

    S3Object s3Object;
    try {
      s3Object = getSourceObject(eventRequest);
    } catch (Exception e) {
      logger.error("Error getting object from S3: {}", e.getMessage(), e);
      response.put("status", "error");
      response.put("message", e.getMessage());
      return response;
    }

    Path tempDir;
    try {
      tempDir = createTempDir();
    } catch (IOException e) {
      logger.error("Failed to create temp directory: {}", e.getMessage(), e);
      response.put("status", "error");
      response.put("message", "Failed to create temp directory");
      return response;
    }

    String originalFileName = eventRequest.getFileName();
    String baseName = stripPdfExtension(originalFileName);
    Path localInputPath;
    try {
      localInputPath = downloadToTemp(s3Object, tempDir, originalFileName);
    } catch (IOException e) {
      logger.error("Failed to download S3 object to temp file: {}", e.getMessage(), e);
      response.put("status", "error");
      response.put("message", "Failed to download source PDF");
      return response;
    }

    Path localOutputDir = prepareLocalOutputDir(tempDir, baseName);

    SplitResult splitResult = performSplit(localInputPath, eventRequest.getSplitRange(), localOutputDir, baseName);
    response.put("status", splitResult.getStatus());
    response.put("message", splitResult.getMessage());
    if (!"success".equals(splitResult.getStatus())) {
      return response;
    }

    String outputS3Folder = buildOutputFolder(eventRequest.getFolderName(), baseName + "_tagged_split");
    List<String> uploadedKeys = uploadAll(eventRequest, outputS3Folder, splitResult.getGeneratedFiles());

    response.put("output_folder", outputS3Folder);
    response.put("generated_files", uploadedKeys);
    return response;
  }

  private S3Object getSourceObject(EventRequest eventRequest) throws Exception {
    return s3Client.getObject(eventRequest.getBucketName(), eventRequest.getFolderName(), eventRequest.getFileName());
  }

  private Path createTempDir() throws IOException {

    return Files.createTempDirectory("pdfsplit_");
  }

  private Path downloadToTemp(S3Object s3Object, Path tempDir, String originalFileName) throws IOException {
    Path localInputPath = tempDir.resolve(originalFileName);
    try (InputStream in = s3Object.getObjectContent(); FileOutputStream out = new FileOutputStream(localInputPath.toFile())) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
    }
    return localInputPath;
  }

  private Path prepareLocalOutputDir(Path tempDir, String baseName) {
    return tempDir.resolve(baseName + "_tagged_split");
  }

  private SplitResult performSplit(Path localInputPath, int splitRange, Path localOutputDir, String baseName) {
    return taggedPdfSplitter.splitTaggedPdf(localInputPath.toString(), splitRange, localOutputDir.toString(), baseName);
  }

  private List<String> uploadAll(EventRequest eventRequest, String outputS3Folder, List<String> localPaths) {
    return localPaths.stream().map(path -> {
      File f = new File(path);
      String keyName = f.getName();
      s3Client.uploadFile(eventRequest.getBucketName(), outputS3Folder, keyName, f);
      return s3Client.buildKey(outputS3Folder, keyName);
    }).collect(Collectors.toList());
  }

  private String stripPdfExtension(String fileName) {
    if (fileName == null) return null;
    if (fileName.toLowerCase().endsWith(".pdf")) {
      return fileName.substring(0, fileName.length() - 4);
    }
    return fileName;
  }

  private String buildOutputFolder(String originalFolder, String newFolderName) {
    if (originalFolder == null || originalFolder.isEmpty()) {
      return newFolderName;
    }
    return originalFolder + "/" + newFolderName;
  }
}