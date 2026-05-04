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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PdfSplitter implements RequestHandler<Map<String, String>, Map<String, Object>> {

  private static final Logger logger = LoggerFactory.getLogger(PdfSplitter.class);
  private static final long DEFAULT_MAX_BYTES = 80L * 1000 * 1000;

  private final PdfSizeCappedSplitter sizeCappedSplitter = new PdfSizeCappedSplitter();
  private final AWSS3Client s3Client = new AWSS3Client();

  @Override
  public Map<String, Object> handleRequest(Map<String, String> input, Context context) {
    Map<String, Object> response = new HashMap<>();

    EventRequest eventRequest = parseEventRequest(input, response);
    if (eventRequest == null) {
      return response;
    }
    logger.info("Event request: bucket={}, folder={}, file={}", eventRequest.getBucketName(), eventRequest.getFolderName(), eventRequest.getFileName());

    if (!validateEventRequestAndRespond(eventRequest, response)) {
      return response;
    }

    S3Object s3Object = fetchS3Object(eventRequest, response);
    if (s3Object == null) {
      return response;
    }

    Path tempDir = createTempDirectory(response);
    if (tempDir == null) {
      return response;
    }

    String originalFileName = eventRequest.getFileName();
    String baseName = stripPdfExtension(originalFileName);
    Path localInputPath = downloadSourceToTemp(s3Object, tempDir, originalFileName, response);
    if (localInputPath == null) {
      return response;
    }

    Path localOutputDir = prepareLocalOutputDir(tempDir, baseName);
    long maxBytes = resolveMaxBytes(input);
    SplitResult splitResult = sizeCappedSplitter.splitByMaxBytes(
        localInputPath.toString(),
        maxBytes,
        localOutputDir.toString(),
        baseName);

    response.put("message", splitResult.getMessage());
    if (!"success".equals(splitResult.getStatus())) {
      response.put("error", true);
      return response;
    }

    String outputS3Folder = buildOutputFolder(eventRequest.getFolderName(), baseName + "_split");
    uploadAll(eventRequest, outputS3Folder, splitResult.getGeneratedFiles());

    response.put("error", false);
    return response;
  }

  private EventRequest parseEventRequest(Map<String, String> input, Map<String, Object> response) {
    try {
      return new EventRequest(input);
    } catch (Exception e) {
      logger.error("Failed to parse event request: {}", e.getMessage(), e);
      response.put("error", true);
      response.put("message", "Invalid request: " + e.getMessage());
      return null;
    }
  }

  private boolean validateEventRequestAndRespond(EventRequest eventRequest, Map<String, Object> response) {
    String validationError = validateEventRequest(eventRequest);
    if (validationError != null) {
      response.put("error", true);
      response.put("message", validationError);
      return false;
    }
    return true;
  }

  private S3Object fetchS3Object(EventRequest eventRequest, Map<String, Object> response) {
    try {
      return getSourceObject(eventRequest);
    } catch (Exception e) {
      logger.error("Error getting object from S3: {}", e.getMessage(), e);
      response.put("error", true);
      response.put("message", e.getMessage());
      return null;
    }
  }

  private Path createTempDirectory(Map<String, Object> response) {
    try {
      return createTempDir();
    } catch (IOException e) {
      logger.error("Failed to create temp directory: {}", e.getMessage(), e);
      response.put("error", true);
      response.put("message", "Failed to create temp directory");
      return null;
    }
  }

  private Path downloadSourceToTemp(S3Object s3Object, Path tempDir, String originalFileName, Map<String, Object> response) {
    try {
      return downloadToTemp(s3Object, tempDir, originalFileName);
    } catch (IOException e) {
      logger.error("Failed to download S3 object to temp file: {}", e.getMessage(), e);
      response.put("error", true);
      response.put("message", "Failed to download source PDF");
      return null;
    }
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
    return tempDir.resolve(baseName + "_split");
  }

  private void uploadAll(EventRequest eventRequest, String outputS3Folder, List<String> localPaths) {
    for (String path : localPaths) {
      File f = new File(path);
      s3Client.uploadFile(eventRequest.getBucketName(), outputS3Folder, f.getName(), f);
    }
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

  private String validateEventRequest(EventRequest req) {
    if (req.getBucketName() == null || req.getBucketName().trim().isEmpty()) {
      return "bucket_name is required";
    }
    if (req.getFileName() == null || req.getFileName().trim().isEmpty()) {
      return "file_name is required";
    }
    return null;
  }

  private long resolveMaxBytes(Map<String, String> input) {
    String maxMbStr = input.get("max_mb");
    if (maxMbStr != null && !maxMbStr.isBlank()) {
      try {
        long mb = Long.parseLong(maxMbStr.trim());
        if (mb > 0) return mb * 1000L * 1000L;
      } catch (NumberFormatException ignored) {}
    }
    return DEFAULT_MAX_BYTES;
  }
}
