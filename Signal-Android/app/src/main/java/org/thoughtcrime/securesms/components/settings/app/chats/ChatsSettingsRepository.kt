package org.thoughtcrime.securesms.components.settings.app.chats

import android.content.Context
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.MultiDeviceConfigurationUpdateJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.megaphone.Megaphones
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.storage.StorageSyncHelper
import org.thoughtcrime.securesms.util.TextSecurePreferences

class ChatsSettingsRepository {

  private val context: Context = ApplicationDependencies.getApplication()

  fun syncLinkPreviewsState() {
    SignalExecutors.BOUNDED.execute {
      val isLinkPreviewsEnabled = SignalStore.settings().isLinkPreviewsEnabled

      SignalDatabase.recipients.markNeedsSync(Recipient.self().id)
      StorageSyncHelper.scheduleSyncForDataChange()
      ApplicationDependencies.getJobManager().add(
        MultiDeviceConfigurationUpdateJob(
          TextSecurePreferences.isReadReceiptsEnabled(context),
          TextSecurePreferences.isTypingIndicatorsEnabled(context),
          TextSecurePreferences.isShowUnidentifiedDeliveryIndicatorsEnabled(context),
          isLinkPreviewsEnabled
        )
      )
      if (isLinkPreviewsEnabled) {
        ApplicationDependencies.getMegaphoneRepository().markFinished(Megaphones.Event.LINK_PREVIEWS)
      }
    }
  }
}
