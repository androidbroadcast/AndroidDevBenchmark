package org.thoughtcrime.securesms.pin;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.KbsEnclave;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.lock.PinHashing;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.KbsPinData;
import org.whispersystems.signalservice.api.KeyBackupService;
import org.whispersystems.signalservice.api.KeyBackupServicePinException;
import org.whispersystems.signalservice.api.KeyBackupSystemNoDataException;
import org.whispersystems.signalservice.api.kbs.HashedPin;
import org.whispersystems.signalservice.internal.ServiceResponse;
import org.whispersystems.signalservice.internal.contacts.crypto.UnauthenticatedResponseException;
import org.whispersystems.signalservice.internal.contacts.entities.TokenResponse;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Using provided or already stored authorization, provides various get token data from KBS
 * and generate {@link KbsPinData}.
 */
public final class KbsRepository {

  private static final String TAG = Log.tag(KbsRepository.class);

  public void getToken(@NonNull Consumer<Optional<TokenData>> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        callback.accept(Optional.fromNullable(getTokenSync(null)));
      } catch (IOException e) {
        callback.accept(Optional.absent());
      }
    });
  }

  /**
   * @param authorization If this is being called before the user is registered (i.e. as part of
   *                      reglock), you must pass in an authorization token that can be used to
   *                      retrieve a backup. Otherwise, pass in null and we'll fetch one.
   */
  public Single<ServiceResponse<TokenData>> getToken(@Nullable String authorization) {
    return Single.<ServiceResponse<TokenData>>fromCallable(() -> {
      try {
        return ServiceResponse.forResult(getTokenSync(authorization), 200, null);
      } catch (IOException e) {
        return ServiceResponse.forUnknownError(e);
      }
    }).subscribeOn(Schedulers.io());
  }

  private @NonNull TokenData getTokenSync(@Nullable String authorization) throws IOException {
    TokenData firstKnownTokenData = null;

    for (KbsEnclave enclave : KbsEnclaves.all()) {
      KeyBackupService kbs = ApplicationDependencies.getKeyBackupService(enclave);

      authorization = authorization == null ? kbs.getAuthorization() : authorization;

      TokenResponse token     = kbs.getToken(authorization);
      TokenData     tokenData = new TokenData(enclave, authorization, token);

      if (tokenData.getTriesRemaining() > 0) {
        Log.i(TAG, "Found data! " + enclave.getEnclaveName());
        return tokenData;
      } else if (firstKnownTokenData == null) {
        Log.i(TAG, "No data, but storing as the first response. " + enclave.getEnclaveName());
        firstKnownTokenData = tokenData;
      } else {
        Log.i(TAG, "No data, and we already have a 'first response'. " + enclave.getEnclaveName());
      }
    }

    return Objects.requireNonNull(firstKnownTokenData);
  }

  /**
   * Invoked during registration to restore the master key based on the server response during
   * verification.
   *
   * Does not affect {@link PinState}.
   */
  public static synchronized @Nullable KbsPinData restoreMasterKey(@Nullable String pin,
                                                                   @NonNull KbsEnclave enclave,
                                                                   @Nullable String basicStorageCredentials,
                                                                   @NonNull TokenResponse tokenResponse)
      throws IOException, KeyBackupSystemWrongPinException, KeyBackupSystemNoDataException
  {
    Log.i(TAG, "restoreMasterKey()");

    if (pin == null) return null;

    if (basicStorageCredentials == null) {
      throw new AssertionError("Cannot restore KBS key, no storage credentials supplied");
    }

    Log.i(TAG, "Preparing to restore from " + enclave.getEnclaveName());
    return restoreMasterKeyFromEnclave(enclave, pin, basicStorageCredentials, tokenResponse);
  }

  private static @NonNull KbsPinData restoreMasterKeyFromEnclave(@NonNull KbsEnclave enclave,
                                                                 @NonNull String pin,
                                                                 @NonNull String basicStorageCredentials,
                                                                 @NonNull TokenResponse tokenResponse)
      throws IOException, KeyBackupSystemWrongPinException, KeyBackupSystemNoDataException
  {
    KeyBackupService                keyBackupService = ApplicationDependencies.getKeyBackupService(enclave);
    KeyBackupService.RestoreSession session          = keyBackupService.newRegistrationSession(basicStorageCredentials, tokenResponse);

    try {
      Log.i(TAG, "Restoring pin from KBS");

      HashedPin  hashedPin = PinHashing.hashPin(pin, session);
      KbsPinData kbsData   = session.restorePin(hashedPin);

      if (kbsData != null) {
        Log.i(TAG, "Found registration lock token on KBS.");
      } else {
        throw new AssertionError("Null not expected");
      }

      return kbsData;
    } catch (UnauthenticatedResponseException | InvalidKeyException e) {
      Log.w(TAG, "Failed to restore key", e);
      throw new IOException(e);
    } catch (KeyBackupServicePinException e) {
      Log.w(TAG, "Incorrect pin", e);
      throw new KeyBackupSystemWrongPinException(e.getToken());
    }
  }
}
