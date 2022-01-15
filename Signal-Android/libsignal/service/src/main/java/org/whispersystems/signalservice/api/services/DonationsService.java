package org.whispersystems.signalservice.api.services;

import org.signal.zkgroup.receipts.ReceiptCredentialPresentation;
import org.signal.zkgroup.receipts.ReceiptCredentialRequest;
import org.signal.zkgroup.receipts.ReceiptCredentialResponse;
import org.whispersystems.libsignal.logging.Log;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations;
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.subscriptions.ActiveSubscription;
import org.whispersystems.signalservice.api.subscriptions.SubscriberId;
import org.whispersystems.signalservice.api.subscriptions.SubscriptionClientSecret;
import org.whispersystems.signalservice.api.subscriptions.SubscriptionLevels;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.internal.EmptyResponse;
import org.whispersystems.signalservice.internal.ServiceResponse;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.push.DonationIntentResult;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * One-stop shop for Signal service calls related to donations.
 */
public class DonationsService {

  private static final String TAG = DonationsService.class.getSimpleName();

  private final PushServiceSocket pushServiceSocket;

  public DonationsService(
      SignalServiceConfiguration configuration,
      CredentialsProvider credentialsProvider,
      String signalAgent,
      GroupsV2Operations groupsV2Operations,
      boolean automaticNetworkRetry
  ) {
    this(new PushServiceSocket(configuration, credentialsProvider, signalAgent, groupsV2Operations.getProfileOperations(), automaticNetworkRetry));
  }

  // Visible for testing.
  DonationsService(@NonNull PushServiceSocket pushServiceSocket) {
    this.pushServiceSocket = pushServiceSocket;
  }

  /**
   * Allows a user to redeem a given receipt they were given after submitting a donation successfully.
   *
   * @param receiptCredentialPresentation Receipt
   * @param visible                       Whether the badge will be visible on the user's profile immediately after redemption
   * @param primary                       Whether the badge will be made primary immediately after redemption
   */
  public Single<ServiceResponse<EmptyResponse>> redeemReceipt(ReceiptCredentialPresentation receiptCredentialPresentation, boolean visible, boolean primary) {
    return Single.fromCallable(() -> {
      try {
        pushServiceSocket.redeemDonationReceipt(receiptCredentialPresentation, visible, primary);
        return ServiceResponse.forResult(EmptyResponse.INSTANCE, 200, null);
      } catch (Exception e) {
        return ServiceResponse.<EmptyResponse>forUnknownError(e);
      }
    }).subscribeOn(Schedulers.io());
  }

  /**
   * Submits price information to the server to generate a payment intent via the payment gateway.
   *
   * @param amount        Price, in the minimum currency unit (e.g. cents or yen)
   * @param currencyCode  The currency code for the amount
   * @return              A ServiceResponse containing a DonationIntentResult with details given to us by the payment gateway.
   */
  public Single<ServiceResponse<SubscriptionClientSecret>> createDonationIntentWithAmount(String amount, String currencyCode, String description) {
    return createServiceResponse(() -> new Pair<>(pushServiceSocket.createBoostPaymentMethod(currencyCode, Long.parseLong(amount), description), 200));
  }

  /**
   * Given a completed payment intent and a receipt credential request produces a receipt credential response.
   * Clients should always use the same ReceiptCredentialRequest with the same payment intent id. This request is repeatable so long as the two values are reused.
   *
   * @param paymentIntentId          PaymentIntent ID from a boost donation intent response.
   * @param receiptCredentialRequest Client-generated request token
   */
  public Single<ServiceResponse<ReceiptCredentialResponse>> submitBoostReceiptCredentialRequest(String paymentIntentId, ReceiptCredentialRequest receiptCredentialRequest) {
    return createServiceResponse(() -> new Pair<>(pushServiceSocket.submitBoostReceiptCredentials(paymentIntentId, receiptCredentialRequest), 200));
  }

  /**
   * @return The suggested amounts for Signal Boost
   */
  public Single<ServiceResponse<Map<String, List<BigDecimal>>>> getBoostAmounts() {
    return createServiceResponse(() -> new Pair<>(pushServiceSocket.getBoostAmounts(), 200));
  }

  /**
   * @return The badge configuration for signal boost. Expect for right now only a single level numbered 1.
   */
  public Single<ServiceResponse<SignalServiceProfile.Badge>> getBoostBadge(Locale locale) {
    return createServiceResponse(() -> new Pair<>(pushServiceSocket.getBoostLevels(locale).getLevels().get("1").getBadge(), 200));
  }

  /**
   * Returns the subscription levels that are available for the client to choose from along with currencies and current prices
   */
  public Single<ServiceResponse<SubscriptionLevels>> getSubscriptionLevels(Locale locale) {
    return createServiceResponse(() -> new Pair<>(pushServiceSocket.getSubscriptionLevels(locale), 200));
  }

  /**
   * Updates the current subscription to the given level and currency. The idempotency key should be a randomly generated 16-byte value that's
   * url-safe-base64-encoded by the client for each user-operation. That is, if the user is updating from level 500 to level 1000 and the client has to retry
   * the request, the idempotency key should remain the same. However, if the user updates from level 500 to level 1000, then updates from level 1000 to
   * level 500, then updates from level 500 to level 1000 again all three of these operations should have separate idempotency keys. Think of this value as an
   * indicator of user-intention. It should be the same for retries, but any new user-intention to update the subscription should produce a unique value.
   *
   * @param subscriberId   The subscriber ID for the user changing their subscription level
   * @param level          The new level to subscribe to
   * @param currencyCode   The currencyCode the user is using for payment
   * @param idempotencyKey url-safe-base64-encoded random 16-byte value (see description)
   * @param mutex          A mutex to lock on to avoid a situation where this subscription update happens *as* we are trying to get a credential receipt.
   */
  public Single<ServiceResponse<EmptyResponse>> updateSubscriptionLevel(SubscriberId subscriberId,
                                                                        String level,
                                                                        String currencyCode,
                                                                        String idempotencyKey,
                                                                        Object mutex
  ) {
    return createServiceResponse(() -> {
      synchronized(mutex) {
        pushServiceSocket.updateSubscriptionLevel(subscriberId.serialize(), level, currencyCode, idempotencyKey);
      }
      return new Pair<>(EmptyResponse.INSTANCE, 200);
    });
  }

  /**
   * Returns information about the current subscription if one exists.
   */
  public Single<ServiceResponse<ActiveSubscription>> getSubscription(SubscriberId subscriberId) {
    return createServiceResponse(() -> {
      ActiveSubscription response = pushServiceSocket.getSubscription(subscriberId.serialize());
      return new Pair<>(response, 200);
    });
  }

  /**
   * Creates a subscriber record on the signal server and stripe. Can be called idempotently as-is. After receiving 200 from this endpoint,
   * clients should save subscriberId locally and to storage service for the account. If you get a 403 from this endpoint and you did not
   * use an account authenticated connection, then the subscriberId has been corrupted in some way.
   *
   * Clients MUST periodically hit this endpoint to update the access time on the subscription record. Recommend trying to call it approximately
   * every 3 days. Not accessing this endpoint for an extended period of time will result in the subscription being canceled.
   *
   * @param subscriberId  The subscriber ID for the user polling their subscription
   */
  public Single<ServiceResponse<EmptyResponse>> putSubscription(SubscriberId subscriberId) {
    return createServiceResponse(() -> {
      pushServiceSocket.putSubscription(subscriberId.serialize());
      return new Pair<>(EmptyResponse.INSTANCE, 200);
    });
  }

  /**
   * Cancels any current subscription at the end of the current subscription period.
   *
   * @param subscriberId  The subscriber ID for the user cancelling their subscription
   */
  public Single<ServiceResponse<EmptyResponse>> cancelSubscription(SubscriberId subscriberId) {
    return createServiceResponse(() -> {
      pushServiceSocket.deleteSubscription(subscriberId.serialize());
      return new Pair<>(EmptyResponse.INSTANCE, 200);
    });
  }

  public Single<ServiceResponse<EmptyResponse>> setDefaultPaymentMethodId(SubscriberId subscriberId, String paymentMethodId) {
    return createServiceResponse(() -> {
      pushServiceSocket.setDefaultSubscriptionPaymentMethod(subscriberId.serialize(), paymentMethodId);
      return new Pair<>(EmptyResponse.INSTANCE, 200);
    });
  }

  /**
   *
   * @param subscriberId  The subscriber ID to create a payment method for.
   * @return              Client secret for a SetupIntent. It should not be used with the PaymentIntent stripe APIs
   *                      but instead with the SetupIntent stripe APIs.
   */
  public Single<ServiceResponse<SubscriptionClientSecret>> createSubscriptionPaymentMethod(SubscriberId subscriberId) {
    return createServiceResponse(() -> {
      SubscriptionClientSecret clientSecret = pushServiceSocket.createSubscriptionPaymentMethod(subscriberId.serialize());
      return new Pair<>(clientSecret, 200);
    });
  }

  public Single<ServiceResponse<ReceiptCredentialResponse>> submitReceiptCredentialRequest(SubscriberId subscriberId, ReceiptCredentialRequest receiptCredentialRequest) {
    return createServiceResponse(() -> {
      ReceiptCredentialResponse response = pushServiceSocket.submitReceiptCredentials(subscriberId.serialize(), receiptCredentialRequest);
      return new Pair<>(response, 200);
    });
  }

  private <T> Single<ServiceResponse<T>> createServiceResponse(Producer<T> producer) {
    return Single.fromCallable(() -> {
      try {
        Pair<T, Integer> responseAndCode = producer.produce();
        return ServiceResponse.forResult(responseAndCode.first(), responseAndCode.second(), null);
      } catch (NonSuccessfulResponseCodeException e) {
        Log.w(TAG, "Bad response code from server.", e);
        return ServiceResponse.<T>forApplicationError(e, e.getCode(), e.getMessage());
      } catch (IOException e) {
        Log.w(TAG, "An unknown error occurred.", e);
        return ServiceResponse.<T>forUnknownError(e);
      }
    }).subscribeOn(Schedulers.io());
  }

  private interface Producer<T> {
    Pair<T, Integer> produce() throws IOException;
  }
}
