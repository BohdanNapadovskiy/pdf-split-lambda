package com.netralabs.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ValidationResult {
  public final boolean ok;
  public final String message;
  public final long bytes;

  public ValidationResult(boolean ok, String message, long bytes) {
    this.ok = ok; this.message = message; this.bytes = bytes;
  }
  public static ValidationResult ok(long bytes) { return new ValidationResult(true, "OK", bytes); }
  public static ValidationResult fail(String msg, long bytes) { return new ValidationResult(false, msg, bytes); }
}
