package org.thoughtcrime.securesms.contacts.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.contacts.sync.DirectoryHelper.DirectoryResult;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.push.IasTrustStore;
import org.thoughtcrime.securesms.util.SetUtil;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.TrustStore;
import org.whispersystems.signalservice.internal.contacts.crypto.Quote;
import org.whispersystems.signalservice.internal.contacts.crypto.UnauthenticatedQuoteException;
import org.whispersystems.signalservice.internal.contacts.crypto.UnauthenticatedResponseException;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Uses CDS to map E164's to UUIDs.
 */
class ContactDiscoveryV2 {

  private static final String TAG = Log.tag(ContactDiscoveryV2.class);

  private static final int MAX_NUMBERS = 20_500;

  @WorkerThread
  static DirectoryResult getDirectoryResult(@NonNull Context context,
                                            @NonNull Set<String> databaseNumbers,
                                            @NonNull Set<String> systemNumbers)
      throws IOException
  {
    Set<String>                        allNumbers        = SetUtil.union(databaseNumbers, systemNumbers);
    FuzzyPhoneNumberHelper.InputResult inputResult       = FuzzyPhoneNumberHelper.generateInput(allNumbers, databaseNumbers);
    Set<String>                        sanitizedNumbers  = sanitizeNumbers(inputResult.getNumbers());
    Set<String>                        ignoredNumbers    = new HashSet<>();

    if (sanitizedNumbers.size() > MAX_NUMBERS) {
      Set<String> randomlySelected = randomlySelect(sanitizedNumbers, MAX_NUMBERS);

      ignoredNumbers   = SetUtil.difference(sanitizedNumbers, randomlySelected);
      sanitizedNumbers = randomlySelected;
    }

    SignalServiceAccountManager accountManager = ApplicationDependencies.getSignalServiceAccountManager();
    KeyStore                    iasKeyStore    = getIasKeyStore(context);

    try {
      Map<String, ACI>                    results      = accountManager.getRegisteredUsers(iasKeyStore, sanitizedNumbers, BuildConfig.CDS_MRENCLAVE);
      FuzzyPhoneNumberHelper.OutputResult outputResult = FuzzyPhoneNumberHelper.generateOutput(results, inputResult);

      return new DirectoryResult(outputResult.getNumbers(), outputResult.getRewrites(), ignoredNumbers);
    } catch (SignatureException | UnauthenticatedQuoteException | UnauthenticatedResponseException | Quote.InvalidQuoteFormatException |InvalidKeyException e) {
      Log.w(TAG, "Attestation error.", e);
      throw new IOException(e);
    }
  }

  static @NonNull DirectoryResult getDirectoryResult(@NonNull Context context, @NonNull String number) throws  IOException {
    return getDirectoryResult(context, Collections.singleton(number), Collections.singleton(number));
  }

  private static Set<String> sanitizeNumbers(@NonNull Set<String> numbers) {
    return Stream.of(numbers).filter(number -> {
      try {
        return number.startsWith("+") && number.length() > 1 && number.charAt(1) != '0' && Long.parseLong(number.substring(1)) > 0;
      } catch (NumberFormatException e) {
        return false;
      }
    }).collect(Collectors.toSet());
  }

  private static @NonNull Set<String> randomlySelect(@NonNull Set<String> numbers, int max) {
    List<String> list = new ArrayList<>(numbers);
    Collections.shuffle(list);

    return new HashSet<>(list.subList(0, max));
  }

  private static KeyStore getIasKeyStore(@NonNull Context context) {
    try {
      TrustStore contactTrustStore = new IasTrustStore(context);

      KeyStore keyStore = KeyStore.getInstance("BKS");
      keyStore.load(contactTrustStore.getKeyStoreInputStream(), contactTrustStore.getKeyStorePassword().toCharArray());

      return keyStore;
    } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
