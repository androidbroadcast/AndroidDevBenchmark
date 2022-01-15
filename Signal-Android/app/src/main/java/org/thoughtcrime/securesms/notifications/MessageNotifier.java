package org.thoughtcrime.securesms.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import org.signal.core.util.concurrent.SignalExecutors;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.BubbleUtil;

public interface MessageNotifier {
  void setVisibleThread(long threadId);
  long getVisibleThread();
  void clearVisibleThread();
  void setLastDesktopActivityTimestamp(long timestamp);
  void notifyMessageDeliveryFailed(@NonNull Context context, @NonNull Recipient recipient, long threadId);
  void notifyProofRequired(@NonNull Context context, @NonNull Recipient recipient, long threadId);
  void cancelDelayedNotifications();
  void updateNotification(@NonNull Context context);
  void updateNotification(@NonNull Context context, long threadId);
  void updateNotification(@NonNull Context context, long threadId, @NonNull BubbleUtil.BubbleState defaultBubbleState);
  void updateNotification(@NonNull Context context, long threadId, boolean signal);
  void updateNotification(@NonNull Context context, long threadId, boolean signal, int reminderCount, @NonNull BubbleUtil.BubbleState defaultBubbleState);
  void clearReminder(@NonNull Context context);
  void addStickyThread(long threadId, long earliestTimestamp);
  void removeStickyThread(long threadId);


  class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, final Intent intent) {
      SignalExecutors.BOUNDED.execute(() -> {
        int reminderCount = intent.getIntExtra("reminder_count", 0);
        ApplicationDependencies.getMessageNotifier().updateNotification(context, -1, true, reminderCount + 1, BubbleUtil.BubbleState.HIDDEN);
      });
    }
  }
}
