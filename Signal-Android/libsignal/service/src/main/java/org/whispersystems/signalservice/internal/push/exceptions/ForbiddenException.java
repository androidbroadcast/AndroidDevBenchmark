package org.whispersystems.signalservice.internal.push.exceptions;

import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;

public final class ForbiddenException extends NonSuccessfulResponseCodeException {
  public ForbiddenException() {
    super(403);
  }
}
