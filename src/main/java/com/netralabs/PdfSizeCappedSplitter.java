package com.netralabs;

import com.itextpdf.kernel.pdf.PdfCatalog;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfViewerPreferences;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.WriterProperties;
import com.itextpdf.kernel.utils.PdfMerger;
import com.netralabs.domain.SplitResult;
import com.netralabs.domain.ValidationResult;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;


public class PdfSizeCappedSplitter {


  private static final Logger log = LoggerFactory.getLogger(PdfSizeCappedSplitter.class);

  public SplitResult splitByMaxBytes(String inputFilePath, long maxBytes, String outputDir, String baseFileName) {
    List<String> outputs = new ArrayList<>();
    if (maxBytes <= 0) {
      return new SplitResult("error", "maxBytes must be > 0", inputFilePath, null);
    }

    try (PdfDocument inputPdf = new PdfDocument(new PdfReader(inputFilePath))) {
      int total = inputPdf.getNumberOfPages();
      if (total < 1) return new SplitResult("error", "Invalid PDF: no pages", inputFilePath, null);

      Files.createDirectories(Paths.get(outputDir));
      int start = 1;

      while (start <= total) {
        int lo = start, hi = total, best = -1;
        byte[] bestBuf = null;

        while (lo <= hi) {
          int mid = (lo + hi) >>> 1;
          byte[] buf = renderRange(inputPdf, start, mid);
          if (buf.length <= maxBytes) {
            best = mid; bestBuf = buf; lo = mid + 1;    // try to grow
          } else {
            hi = mid - 1;                                // shrink
          }
        }

        if (best == -1) {
          // even a single page is larger than limit
          return new SplitResult(
              "error",
              "Page " + start + " alone exceeds " + maxBytes + " bytes. Consider downsampling or raising the limit.",
              inputFilePath,
              outputs.isEmpty() ? null : outputs
          );
        }

        String outPath = writeFinal(outputDir, baseFileName, start, best, bestBuf);
        outputs.add(outPath);
        log.info("Wrote {} (pages {}..{}, {} bytes)", outPath, start, best, bestBuf.length);

        start = best + 1;
      }

      return new SplitResult("success", "Split completed", inputFilePath, outputs);

    } catch (IOException e) {
      log.error("Split failed", e);
      return new SplitResult("error", "IO error: " + e.getMessage(), inputFilePath, outputs.isEmpty() ? null : outputs);
    }
  }

  private byte[] renderRange(PdfDocument inputPdf, int start, int end) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
    WriterProperties wp = new WriterProperties()
        .setFullCompressionMode(true)
        .useSmartMode()
        .setCompressionLevel(9);
    try (PdfWriter writer = new PdfWriter(baos, wp);
         PdfDocument out = new PdfDocument(writer)) {
      // preserve tagged + Lang + viewer prefs like your current splitter
      out.setTagged();
      PdfCatalog inCat = inputPdf.getCatalog();
      if (inCat.getLang() != null) out.getCatalog().setLang(inCat.getLang());
      PdfViewerPreferences prefs = inCat.getViewerPreferences();
      if (prefs != null) out.getCatalog().setViewerPreferences(prefs);

      new PdfMerger(out).merge(inputPdf, start, end);
    }
    return baos.toByteArray();
  }

  private String writeFinal(String dir, String base, int start, int end, byte[] bytes) throws IOException {
    String fileName = String.format("%s_%d_%d.pdf", base, start, end);
    Path p = Paths.get(dir).resolve(fileName);
    Files.write(p, bytes);
    return p.toAbsolutePath().toString();
  }
}
