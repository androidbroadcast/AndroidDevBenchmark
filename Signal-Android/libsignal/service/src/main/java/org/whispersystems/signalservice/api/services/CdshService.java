package org.whispersystems.signalservice.api.services;

import org.signal.libsignal.hsmenclave.HsmEnclaveClient;
import org.whispersystems.libsignal.logging.Log;
import org.whispersystems.libsignal.util.ByteUtil;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.TrustStore;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.util.Tls12SocketFactory;
import org.whispersystems.signalservice.internal.ServiceResponse;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.util.BlacklistingTrustManager;
import org.whispersystems.signalservice.internal.util.Hex;
import org.whispersystems.signalservice.internal.util.Util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.reactivex.rxjava3.core.Single;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Handles network interactions with CDSH, the HSM-backed CDS service.
 */
public final class CdshService {

  private static final String TAG = CdshService.class.getSimpleName();

  private static final int VERSION = 1;

  private final OkHttpClient     client;
  private final HsmEnclaveClient enclave;
  private final String           baseUrl;
  private final String           hexPublicKey;
  private final String           hexCodeHash;

  public CdshService(SignalServiceConfiguration configuration, String hexPublicKey, String hexCodeHash) {
    this.baseUrl      = configuration.getSignalCdshUrls()[0].getUrl();
    this.hexPublicKey = hexPublicKey;
    this.hexCodeHash  = hexCodeHash;

    Pair<SSLSocketFactory, X509TrustManager> socketFactory = createTlsSocketFactory(configuration.getSignalCdshUrls()[0].getTrustStore());

    this.client = new OkHttpClient.Builder().sslSocketFactory(new Tls12SocketFactory(socketFactory.first()),
                                                              socketFactory.second())
                                            .connectionSpecs(Util.immutableList(ConnectionSpec.RESTRICTED_TLS))
                                            .readTimeout(30, TimeUnit.SECONDS)
                                            .connectTimeout(30, TimeUnit.SECONDS)
                                            .build();


    try {
      this.enclave = new HsmEnclaveClient(Hex.fromStringCondensed(hexPublicKey),
                                          Collections.singletonList(Hex.fromStringCondensed(hexCodeHash)));
    } catch (IOException e) {
      throw new IllegalArgumentException("Badly-formatted public key or code hash!", e);
    }
  }

  public Single<ServiceResponse<Map<String, ACI>>> getRegisteredUsers(String username, String password, Set<String> e164Numbers) {
    return Single.create(emitter -> {
      AtomicReference<Stage> stage       = new AtomicReference<>(Stage.WAITING_TO_INITIALIZE);
      List<String>           addressBook = e164Numbers.stream().map(e -> e.substring(1)).collect(Collectors.toList());

      String    url       = String.format("%s/discovery/%s/%s", baseUrl, hexPublicKey, hexCodeHash);
      Request   request   = new Request.Builder().url(url).build();
      WebSocket webSocket = client.newWebSocket(request, new WebSocketListener() {
        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
          switch (stage.get()) {
            case WAITING_TO_INITIALIZE:
              enclave.completeHandshake(bytes.toByteArray());

              byte[] request = enclave.establishedSend(buildPlaintextRequest(username, password, addressBook));

              stage.set(Stage.WAITING_FOR_RESPONSE);
              webSocket.send(ByteString.of(request));

              break;
            case WAITING_FOR_RESPONSE:
              byte[] response = enclave.establishedRecv(bytes.toByteArray());

              try {
                Map<String, ACI> out = parseResponse(addressBook, response);
                emitter.onSuccess(ServiceResponse.forResult(out, 200, null));
              } catch (IOException e) {
                emitter.onSuccess(ServiceResponse.forUnknownError(e));
              } finally {
                webSocket.close(1000, "OK");
              }

              break;
            case FAILURE:
              Log.w(TAG, "Received a message after we entered the failure state! Ignoring.");
              webSocket.close(1000, "OK");
              break;
          }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
          if (code != 1000) {
            Log.w(TAG, "Remote side is closing with non-normal code " + code);
            webSocket.close(1000, "Remote closed with code " + code);
            stage.set(Stage.FAILURE);
            emitter.onSuccess(ServiceResponse.forApplicationError(new NonSuccessfulResponseCodeException(code), code, null));
          }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
          emitter.onSuccess(ServiceResponse.forApplicationError(t, response != null ? response.code() : 0, null));
          stage.set(Stage.FAILURE);
          webSocket.close(1000, "OK");
        }
      });

      webSocket.send(ByteString.of(enclave.initialRequest()));
      emitter.setCancellable(() -> webSocket.close(1000, "OK"));
    });
  }

  private static byte[] buildPlaintextRequest(String username, String password, List<String> addressBook) {
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      outputStream.write(VERSION);
      outputStream.write(username.getBytes(StandardCharsets.UTF_8));
      outputStream.write(password.getBytes(StandardCharsets.UTF_8));

      for (String e164 : addressBook) {
        outputStream.write(ByteUtil.longToByteArray(Long.parseLong(e164)));
      }

      return outputStream.toByteArray();
    } catch (IOException e) {
      throw new AssertionError("Failed to write bytes to the output stream?");
    }
  }

  private static Map<String, ACI> parseResponse(List<String> addressBook, byte[] plaintextResponse) throws IOException {
    Map<String, ACI> results = new HashMap<>();

    try (DataInputStream uuidInputStream = new DataInputStream(new ByteArrayInputStream(plaintextResponse))) {
      for (String candidate : addressBook) {
        long candidateUuidHigh = uuidInputStream.readLong();
        long candidateUuidLow  = uuidInputStream.readLong();
        if (candidateUuidHigh != 0 || candidateUuidLow != 0) {
          results.put('+' + candidate, ACI.from(new UUID(candidateUuidHigh, candidateUuidLow)));
        }
      }
    }

    return results;
  }

  private static Pair<SSLSocketFactory, X509TrustManager> createTlsSocketFactory(TrustStore trustStore) {
    try {
      SSLContext     context       = SSLContext.getInstance("TLS");
      TrustManager[] trustManagers = BlacklistingTrustManager.createFor(trustStore);
      context.init(null, trustManagers, null);

      return new Pair<>(context.getSocketFactory(), (X509TrustManager) trustManagers[0]);
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      throw new AssertionError(e);
    }
  }

  private enum Stage {
    WAITING_TO_INITIALIZE, WAITING_FOR_RESPONSE, FAILURE
  }
}
