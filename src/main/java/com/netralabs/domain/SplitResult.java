package com.netralabs.domain;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SplitResult {
  public String status;
  public String message;
  public String resourceFile;
  public List<String> generatedFiles;

  public SplitResult(String status, String message, String resourceFile, List<String> generatedFiles) {
    this.status = status;
    this.message = message;
    this.resourceFile = resourceFile;
    this.generatedFiles = generatedFiles;
  }

}
