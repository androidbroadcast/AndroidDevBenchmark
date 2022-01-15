package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.Log;
import org.signal.zkgroup.receipts.ReceiptCredentialPresentation;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.subscription.DonorBadgeNotifications;
import org.whispersystems.signalservice.internal.EmptyResponse;
import org.whispersystems.signalservice.internal.ServiceResponse;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Job to redeem a verified donation receipt. It is up to the Job prior in the chain to specify a valid
 * presentation object via setOutputData. This is expected to be the byte[] blob of a ReceiptCredentialPresentation object.
 */
public class DonationReceiptRedemptionJob extends BaseJob {
  private static final String TAG = Log.tag(DonationReceiptRedemptionJob.class);

  public static final String SUBSCRIPTION_QUEUE                    = "ReceiptRedemption";
  public static final String KEY                                   = "DonationReceiptRedemptionJob";
  public static final String INPUT_RECEIPT_CREDENTIAL_PRESENTATION = "data.receipt.credential.presentation";
  public static final String INPUT_PAYMENT_FAILURE                 = "data.payment.failure";
  public static final String INPUT_KEEP_ALIVE_409                  = "data.keep.alive.409";

  public static DonationReceiptRedemptionJob createJobForSubscription() {
    return new DonationReceiptRedemptionJob(
        new Job.Parameters
            .Builder()
            .addConstraint(NetworkConstraint.KEY)
            .setQueue(SUBSCRIPTION_QUEUE)
            .setMaxAttempts(Parameters.UNLIMITED)
            .setMaxInstancesForQueue(1)
            .setLifespan(TimeUnit.DAYS.toMillis(1))
            .build());
  }

  public static DonationReceiptRedemptionJob createJobForBoost() {
    return new DonationReceiptRedemptionJob(
        new Job.Parameters
            .Builder()
            .addConstraint(NetworkConstraint.KEY)
            .setQueue("BoostReceiptRedemption")
            .setMaxAttempts(Parameters.UNLIMITED)
            .setLifespan(TimeUnit.DAYS.toMillis(1))
            .build());
  }

  private DonationReceiptRedemptionJob(@NonNull Job.Parameters parameters) {
    super(parameters);
  }

  @Override
  public @NonNull Data serialize() {
    return Data.EMPTY;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onFailure() {
    Data inputData = getInputData();

    if (inputData != null && inputData.getBooleanOrDefault(INPUT_PAYMENT_FAILURE, false)) {
      DonorBadgeNotifications.PaymentFailed.INSTANCE.show(context);
    } else if (inputData != null && inputData.getBooleanOrDefault(INPUT_KEEP_ALIVE_409, false)) {
      Log.i(TAG, "Skipping redemption due to 409 error during keep-alive.");
      return;
    } else {
      DonorBadgeNotifications.RedemptionFailed.INSTANCE.show(context);
    }

    if (isForSubscription()) {
      Log.d(TAG, "Marking subscription failure", true);
      SignalStore.donationsValues().markSubscriptionRedemptionFailed();
      MultiDeviceSubscriptionSyncRequestJob.enqueue();
    }
  }

  @Override
  protected void onRun() throws Exception {
    Data inputData = getInputData();

    if (inputData == null) {
      Log.w(TAG, "No input data. Exiting.", null, true);
      return;
    }

    if (inputData.getBooleanOrDefault(INPUT_PAYMENT_FAILURE, false)) {
      throw new Exception("Payment failed.");
    }

    byte[] presentationBytes = inputData.getStringAsBlob(INPUT_RECEIPT_CREDENTIAL_PRESENTATION);
    if (presentationBytes == null) {
      Log.d(TAG, "No response data. Exiting.", null, true);
      return;
    }

    ReceiptCredentialPresentation presentation = new ReceiptCredentialPresentation(presentationBytes);

    Log.d(TAG, "Attempting to redeem token... isForSubscription: " + isForSubscription(), true);
    ServiceResponse<EmptyResponse> response = ApplicationDependencies.getDonationsService()
                                                                     .redeemReceipt(presentation,
                                                                                    SignalStore.donationsValues().getDisplayBadgesOnProfile(),
                                                                                    false)
                                                                     .blockingGet();

    if (response.getApplicationError().isPresent()) {
      if (response.getStatus() >= 500) {
        Log.w(TAG, "Encountered a server exception " + response.getStatus(), response.getApplicationError().get(), true);
        throw new RetryableException();
      } else {
        Log.w(TAG, "Encountered a non-recoverable exception " + response.getStatus(), response.getApplicationError().get(), true);
        throw new IOException(response.getApplicationError().get());
      }
    } else if (response.getExecutionError().isPresent()) {
      Log.w(TAG, "Encountered a retryable exception", response.getExecutionError().get(), true);
      throw new RetryableException();
    }

    Log.i(TAG, "Successfully redeemed token with response code " + response.getStatus() + "... isForSubscription: " + isForSubscription(), true);

    if (isForSubscription()) {
      Log.d(TAG, "Clearing subscription failure", true);
      SignalStore.donationsValues().clearSubscriptionRedemptionFailed();
    }
  }

  private boolean isForSubscription() {
    return Objects.equals(getParameters().getQueue(), SUBSCRIPTION_QUEUE);
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof RetryableException;
  }

  private final static class RetryableException extends Exception {
  }

  public static class Factory implements Job.Factory<DonationReceiptRedemptionJob> {
    @Override
    public @NonNull DonationReceiptRedemptionJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new DonationReceiptRedemptionJob(parameters);
    }
  }
}
