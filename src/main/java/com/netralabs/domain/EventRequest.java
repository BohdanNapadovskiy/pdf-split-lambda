package com.netralabs.domain;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class EventRequest {

  private String bucketName;
  private String folderName;
  private String fileName;

  public EventRequest(Map<String, String> input) {
    this.bucketName = input.get("bucket_name");
    this.folderName = input.get("folder_name");
    this.fileName = input.get("file_name");
  }

}
