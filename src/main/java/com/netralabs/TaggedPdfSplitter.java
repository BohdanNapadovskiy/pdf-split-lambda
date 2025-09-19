package com.netralabs;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfViewerPreferences;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.utils.PdfMerger;
import com.netralabs.domain.SplitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class TaggedPdfSplitter {

  private static final Logger logger = LoggerFactory.getLogger(TaggedPdfSplitter.class);

  public SplitResult splitTaggedPdf(String inputFilePath, int pagesPerSplit, String outputDir, String baseFileName) {
    List<String> generatedFiles = new ArrayList<>();
    if (pagesPerSplit < 1) {
      return new SplitResult("error", "Invalid split value: must be 1 or greater.", inputFilePath, null);
    }

    try (PdfDocument inputPdf = new PdfDocument(new PdfReader(inputFilePath))) {
//      if (!inputPdf.isTagged()) {
//        return new SplitResult("error", "Input PDF is not tagged.", inputFilePath, null);
//      }

      int totalPages = inputPdf.getNumberOfPages();
      if (totalPages <= 1) {
        return new SplitResult("error", "Cannot split a file with only one page.", inputFilePath, null);
      }

      Path outDir = Paths.get(outputDir);
      Files.createDirectories(outDir);

      for (int start = 1; start <= totalPages; start += pagesPerSplit) {
        int end = Math.min(start + pagesPerSplit - 1, totalPages);
        String outputFileName = baseFileName + "_" + start + "_" + end + ".pdf";
        Path outputPath = outDir.resolve(outputFileName);
        String absolute = outputPath.toFile().getAbsolutePath();
        generatedFiles.add(absolute);

        try (PdfDocument outputPdf = new PdfDocument(new PdfWriter(absolute))) {
          outputPdf.setTagged();
          outputPdf.getCatalog().setLang(inputPdf.getCatalog().getLang());

          PdfViewerPreferences prefs = inputPdf.getCatalog().getViewerPreferences();
          if (prefs != null) {
            outputPdf.getCatalog().setViewerPreferences(prefs);
          }

          PdfMerger merger = new PdfMerger(outputPdf);
          merger.merge(inputPdf, start, end);
        }
      }

      return new SplitResult("success", "Split completed.", inputFilePath, generatedFiles);

    } catch (IOException e) {
      logger.error("Error splitting PDF: {}", e.getMessage(), e);
      return new SplitResult("error", "IO error: " + e.getMessage(), inputFilePath, null);
    }
  }

}
