package com.netralabs;

import com.netralabs.domain.SplitResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LocalRunner {

  // ---- Edit these for local runs ----
  private static final String INPUT_FILE = "C:\\projects\\pdf\\NMFS_RepCong_fish-vessel-reg_1996_AOD.pdf";
  private static final String OUTPUT_DIR = "C:\\projects\\pdf\\data\\";   // empty = <input-dir>/<basename>_split
  private static final int MAX_MB = 80;
  // -----------------------------------

  public static void main(String[] args) throws Exception {
    Path inputPath = Paths.get(INPUT_FILE).toAbsolutePath();
    if (!Files.isRegularFile(inputPath)) {
      System.err.println("Input file not found: " + inputPath);
      System.exit(1);
    }

    String fileName = inputPath.getFileName().toString();
    String baseName = fileName.toLowerCase().endsWith(".pdf")
        ? fileName.substring(0, fileName.length() - 4)
        : fileName;

    Path outputDir = OUTPUT_DIR.isEmpty()
        ? inputPath.getParent().resolve(baseName + "_split")
        : Paths.get(OUTPUT_DIR).toAbsolutePath();
    Files.createDirectories(outputDir);

    long maxBytes = MAX_MB * 1024L * 1024L;
    SplitResult result = new PdfSizeCappedSplitter().splitByMaxBytes(
        inputPath.toString(), maxBytes, outputDir.toString(), baseName);

    System.out.println("status:  " + result.getStatus());
    System.out.println("message: " + result.getMessage());
    if (result.getGeneratedFiles() != null) {
      System.out.println("files:");
      result.getGeneratedFiles().forEach(p -> System.out.println("  " + p));
    }

    if (!"success".equals(result.getStatus())) {
      System.exit(1);
    }
  }
}
