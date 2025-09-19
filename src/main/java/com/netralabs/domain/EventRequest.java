package com.netralabs.domain;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class EventRequest {

  private int splitRange;
  private String bucketName;
  private String folderName;
  private String fileName;
  private String outputFolder;

  public EventRequest(Map<String, String> input) {
    this.splitRange = input.get("split_range") != null ? Integer.parseInt(input.get("split_range")) : 1;
    this.bucketName = input.get("bucket_name");
    this.folderName = input.get("folder_name");
    this.fileName = input.get("file_name");
    this.outputFolder = "splitFiles";

  }

}
