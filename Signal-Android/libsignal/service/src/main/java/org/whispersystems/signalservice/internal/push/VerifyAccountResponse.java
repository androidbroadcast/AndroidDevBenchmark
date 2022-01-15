package org.whispersystems.signalservice.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

public class VerifyAccountResponse {
  @JsonProperty
  private String uuid;

  @JsonProperty
  private String pni;

  @JsonProperty
  private boolean storageCapable;

  public String getUuid() {
    return uuid;
  }

  public boolean isStorageCapable() {
    return storageCapable;
  }

  public String getPni() {
    return pni;
  }
}
