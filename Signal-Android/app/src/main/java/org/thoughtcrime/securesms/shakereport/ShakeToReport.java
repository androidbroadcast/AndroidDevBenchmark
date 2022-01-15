package org.thoughtcrime.securesms.shakereport;

import android.app.Activity;
import android.app.Application;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.signal.core.util.ShakeDetector;
import org.signal.core.util.ThreadUtil;
import org.signal.core.util.logging.Log;
import org.signal.core.util.tracing.Tracer;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.logsubmit.SubmitDebugLogRepository;
import org.thoughtcrime.securesms.sharing.ShareIntents;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.ServiceUtil;
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog;

import java.lang.ref.WeakReference;

/**
 * A class that will detect a shake and then prompts the user to submit a debuglog. Basically a
 * shortcut to submit a debuglog from anywhere.
 */
public final class ShakeToReport implements ShakeDetector.Listener {

  private static final String TAG = Log.tag(ShakeToReport.class);

  private final Application   application;
  private final ShakeDetector detector;

  private WeakReference<Activity> weakActivity;

  public ShakeToReport(@NonNull Application application) {
    this.application  = application;
    this.detector     = new ShakeDetector(this);
    this.weakActivity = new WeakReference<>(null);
  }

  public void enable() {
    if (!SignalStore.internalValues().shakeToReport()) return;

    detector.start(ServiceUtil.getSensorManager(application));
  }

  public void disable() {
    if (!SignalStore.internalValues().shakeToReport()) return;

    detector.stop();
  }

  public void registerActivity(@NonNull Activity activity) {
    if (!SignalStore.internalValues().shakeToReport()) return;

    this.weakActivity = new WeakReference<>(activity);
  }

  @Override
  public void onShakeDetected() {
    if (!SignalStore.internalValues().shakeToReport()) return;

    Activity activity = weakActivity.get();
    if (activity == null) {
      Log.w(TAG, "No registered activity!");
      return;
    }

    disable();

    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.ShakeToReport_shake_detected)
        .setMessage(R.string.ShakeToReport_submit_debug_log)
        .setNegativeButton(android.R.string.cancel, (d, i) -> {
          d.dismiss();
          enableIfVisible();
        })
        .setPositiveButton(R.string.ShakeToReport_submit, (d, i) -> {
          d.dismiss();
          submitLog(activity);
        })
        .show();
  }

  private void submitLog(@NonNull Activity activity) {
    AlertDialog              spinner = SimpleProgressDialog.show(activity);
    SubmitDebugLogRepository repo    = new SubmitDebugLogRepository();

    Log.i(TAG, "Submitting log...");

    repo.buildAndSubmitLog(url -> {
      Log.i(TAG, "Logs uploaded!");

      ThreadUtil.runOnMain(() -> {
        spinner.dismiss();

        if (url.isPresent()) {
          showPostSubmitDialog(activity, url.get());
        } else {
          Toast.makeText(activity, R.string.ShakeToReport_failed_to_submit, Toast.LENGTH_SHORT).show();
          enableIfVisible();
        }
      });
    });
  }

  private void showPostSubmitDialog(@NonNull Activity activity, @NonNull String url) {
    AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.ShakeToReport_success)
        .setMessage(url)
        .setNegativeButton(android.R.string.cancel, (d, i) -> {
          d.dismiss();
          enableIfVisible();
        })
        .setPositiveButton(R.string.ShakeToReport_share, (d, i) -> {
          d.dismiss();
          enableIfVisible();

          activity.startActivity(new ShareIntents.Builder(activity)
                                                 .setText(url)
                                                 .build());
        })
        .show();

    ((TextView) dialog.findViewById(android.R.id.message)).setTextIsSelectable(true);
  }

  private void enableIfVisible() {
    if (ApplicationDependencies.getAppForegroundObserver().isForegrounded()) {
      enable();
    }
  }
}
