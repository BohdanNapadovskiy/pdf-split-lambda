package com.netralabs;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.model.S3Object;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
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
    Map<String, Object> response = new HashMap<>();

    EventRequest eventRequest = parseEventRequest(input, response);
    if (eventRequest == null) {
      return response;
    }
    logger.info("Event request: splitRange={}, bucket={}, folder={}, file={}", eventRequest.getSplitRange(), eventRequest.getBucketName(), eventRequest.getFolderName(), eventRequest.getFileName());

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

    if (!validateSplitRangeAgainstPdf(localInputPath, eventRequest.getSplitRange(), response)) {
      return response;
    }

    // 7) Split and upload
    Path localOutputDir = prepareLocalOutputDir(tempDir, baseName);

    Long maxBytes = resolveMaxBytes(input);
    SplitResult splitResult;
    if (maxBytes != null) {
      // size-capped mode
      PdfSizeCappedSplitter sizeSplitter = new PdfSizeCappedSplitter();
      splitResult = sizeSplitter.splitByMaxBytes(
          localInputPath.toString(),
          maxBytes,
          localOutputDir.toString(),
          baseName);
    } else {
      splitResult = performSplit(localInputPath, eventRequest.getSplitRange(), localOutputDir, baseName);
    }

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

  private EventRequest parseEventRequest(Map<String, String> input, Map<String, Object> response) {
    try {
      return new EventRequest(input);
    } catch (NumberFormatException nfe) {
      logger.error("Invalid split_range provided: {}", input.get("split_range"));
      response.put("status", "error");
      response.put("message", "Invalid split_range: must be an integer >= 1");
      return null;
    }
  }

  private boolean validateEventRequestAndRespond(EventRequest eventRequest, Map<String, Object> response) {
    String validationError = validateEventRequest(eventRequest);
    if (validationError != null) {
      response.put("status", "error");
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
      response.put("status", "error");
      response.put("message", e.getMessage());
      return null;
    }
  }

  private Path createTempDirectory(Map<String, Object> response) {
    try {
      return createTempDir();
    } catch (IOException e) {
      logger.error("Failed to create temp directory: {}", e.getMessage(), e);
      response.put("status", "error");
      response.put("message", "Failed to create temp directory");
      return null;
    }
  }

  private Path downloadSourceToTemp(S3Object s3Object, Path tempDir, String originalFileName, Map<String, Object> response) {
    try {
      return downloadToTemp(s3Object, tempDir, originalFileName);
    } catch (IOException e) {
      logger.error("Failed to download S3 object to temp file: {}", e.getMessage(), e);
      response.put("status", "error");
      response.put("message", "Failed to download source PDF");
      return null;
    }
  }

  private boolean validateSplitRangeAgainstPdf(Path localInputPath, int splitRange, Map<String, Object> response) {
    try {
      int totalPages = getPdfPageCount(localInputPath);
      if (totalPages <= 0) {
        response.put("status", "error");
        response.put("message", "Invalid PDF: could not determine number of pages");
        return false;
      }
      if (splitRange > totalPages) {
        logger.info("split_range ({}) exceeds total pages ({}). Proceeding to output a single file with all pages.", splitRange, totalPages);
      }
      return true;
    } catch (IOException e) {
      logger.error("Failed to read PDF for page count: {}", e.getMessage(), e);
      response.put("status", "error");
      response.put("message", "Failed to read PDF for page count");
      return false;
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

  private String validateEventRequest(EventRequest req) {
    if (req.getBucketName() == null || req.getBucketName().trim().isEmpty()) {
      return "bucket_name is required";
    }
    if (req.getFileName() == null || req.getFileName().trim().isEmpty()) {
      return "file_name is required";
    }
    if (req.getSplitRange() < 1) {
      return "split_range must be 1 or greater";
    }
    return null;
  }

  private int getPdfPageCount(Path pdfPath) throws IOException {
    try (PdfDocument doc = new PdfDocument(new PdfReader(pdfPath.toString()))) {
      return doc.getNumberOfPages();
    }
  }

  private Long resolveMaxBytes(Map<String, String> input) {
    String maxMbStr = input.get("max_mb");
    if (maxMbStr != null && !maxMbStr.isBlank()) {
      try {
        long mb = Long.parseLong(maxMbStr.trim());
        return mb > 0 ? mb * 1024L * 1024L : null;
      } catch (NumberFormatException ignored) {}
    }
    return null;
  }
}