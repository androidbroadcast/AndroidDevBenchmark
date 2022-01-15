package org.thoughtcrime.securesms.migrations;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.stickers.BlessedPacks;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.VersionTracker;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages application-level migrations.
 *
 * Migrations can be slotted to occur based on changes in the canonical version code
 * (see {@link Util#getCanonicalVersionCode()}).
 *
 * Migrations are performed via {@link MigrationJob}s. These jobs are durable and are run before any
 * other job, allowing you to schedule safe migrations. Furthermore, you may specify that a
 * migration is UI-blocking, at which point we will show a spinner via
 * {@link ApplicationMigrationActivity} if the user opens the app while the migration is in
 * progress.
 */
public class ApplicationMigrations {

  private static final String TAG = Log.tag(ApplicationMigrations.class);

  private static final MutableLiveData<Boolean> UI_BLOCKING_MIGRATION_RUNNING = new MutableLiveData<>();

  private static final int LEGACY_CANONICAL_VERSION = 455;

  @VisibleForTesting
  static final class Version {
    static final int LEGACY                        = 1;
    static final int RECIPIENT_ID                  = 2;
    static final int RECIPIENT_SEARCH              = 3;
    static final int RECIPIENT_CLEANUP             = 4;
    static final int AVATAR_MIGRATION              = 5;
    static final int UUIDS                         = 6;
    static final int CACHED_ATTACHMENTS            = 7;
    static final int STICKERS_LAUNCH               = 8;
    //static final int TEST_ARGON2          = 9;
    static final int SWOON_STICKERS                = 10;
    static final int STORAGE_SERVICE               = 11;
    //static final int STORAGE_KEY_ROTATE   = 12;
    static final int REMOVE_AVATAR_ID              = 13;
    static final int STORAGE_CAPABILITY            = 14;
    static final int PIN_REMINDER                  = 15;
    static final int VERSIONED_PROFILE             = 16;
    static final int PIN_OPT_OUT                   = 17;
    static final int TRIM_SETTINGS                 = 18;
    static final int THUMBNAIL_CLEANUP             = 19;
    static final int GV2                           = 20;
    static final int GV2_2                         = 21;
    static final int CDS                           = 22;
    static final int BACKUP_NOTIFICATION           = 23;
    static final int GV1_MIGRATION                 = 24;
    static final int USER_NOTIFICATION             = 25;
    static final int DAY_BY_DAY_STICKERS           = 26;
    static final int BLOB_LOCATION                 = 27;
    static final int SYSTEM_NAME_SPLIT             = 28;
    // Versions 29, 30 accidentally skipped
    static final int MUTE_SYNC                     = 31;
    static final int PROFILE_SHARING_UPDATE        = 32;
    static final int SMS_STORAGE_SYNC              = 33;
    static final int APPLY_UNIVERSAL_EXPIRE        = 34;
    static final int SENDER_KEY                    = 35;
    static final int SENDER_KEY_2                  = 36;
    static final int DB_AUTOINCREMENT              = 37;
    static final int ATTACHMENT_CLEANUP            = 38;
    static final int LOG_CLEANUP                   = 39;
    static final int ATTACHMENT_CLEANUP_2          = 40;
    static final int ANNOUNCEMENT_GROUP_CAPABILITY = 41;
    static final int STICKER_MY_DAILY_LIFE         = 42;
    static final int SENDER_KEY_3                  = 43;
    static final int CHANGE_NUMBER_SYNC            = 44;
    static final int CHANGE_NUMBER_CAPABILITY      = 45;
    static final int CHANGE_NUMBER_CAPABILITY_2    = 46;
    static final int DEFAULT_REACTIONS_SYNC        = 47;
    static final int DB_REACTIONS_MIGRATION        = 48;
    //static final int CHANGE_NUMBER_CAPABILITY_3  = 49;
    static final int PNI                           = 50;
  }

  public static final int CURRENT_VERSION = 50;

  /**
   * This *must* be called after the {@link JobManager} has been instantiated, but *before* the call
   * to {@link JobManager#beginJobLoop()}. Otherwise, other non-migration jobs may have started
   * executing before we add the migration jobs.
   */
  public static void onApplicationCreate(@NonNull Context context, @NonNull JobManager jobManager) {
    if (isLegacyUpdate(context)) {
      Log.i(TAG, "Detected the need for a legacy update. Last seen canonical version: " + VersionTracker.getLastSeenVersion(context));
      TextSecurePreferences.setAppMigrationVersion(context, 0);
    }

    if (!isUpdate(context)) {
      Log.d(TAG, "Not an update. Skipping.");
      VersionTracker.updateLastSeenVersion(context);
      return;
    } else {
      Log.d(TAG, "About to update. Clearing deprecation flag.");
      SignalStore.misc().clearClientDeprecated();
    }

    final int lastSeenVersion = TextSecurePreferences.getAppMigrationVersion(context);
    Log.d(TAG, "currentVersion: " + CURRENT_VERSION + ",  lastSeenVersion: " + lastSeenVersion);

    LinkedHashMap<Integer, MigrationJob> migrationJobs = getMigrationJobs(context, lastSeenVersion);

    if (migrationJobs.size() > 0) {
      Log.i(TAG, "About to enqueue " + migrationJobs.size() + " migration(s).");

      boolean uiBlocking        = true;
      int     uiBlockingVersion = lastSeenVersion;

      for (Map.Entry<Integer, MigrationJob> entry : migrationJobs.entrySet()) {
        int          version = entry.getKey();
        MigrationJob job     = entry.getValue();

        uiBlocking &= job.isUiBlocking();
        if (uiBlocking) {
          uiBlockingVersion = version;
        }

        jobManager.add(job);
        jobManager.add(new MigrationCompleteJob(version));
      }

      if (uiBlockingVersion > lastSeenVersion) {
        Log.i(TAG, "Migration set is UI-blocking through version " + uiBlockingVersion + ".");
        UI_BLOCKING_MIGRATION_RUNNING.setValue(true);
      } else {
        Log.i(TAG, "Migration set is non-UI-blocking.");
        UI_BLOCKING_MIGRATION_RUNNING.setValue(false);
      }

      final long startTime = System.currentTimeMillis();
      final int  uiVersion = uiBlockingVersion;

      EventBus.getDefault().register(new Object() {
        @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
        public void onMigrationComplete(MigrationCompleteEvent event) {
          Log.i(TAG, "Received MigrationCompleteEvent for version " + event.getVersion() + ". (Current: " + CURRENT_VERSION + ")");

          if (event.getVersion() > CURRENT_VERSION) {
            throw new AssertionError("Received a higher version than the current version? App downgrades are not supported. (received: " + event.getVersion() + ", current: " + CURRENT_VERSION + ")");
          }

          Log.i(TAG, "Updating last migration version to " + event.getVersion());
          TextSecurePreferences.setAppMigrationVersion(context, event.getVersion());

          if (event.getVersion() == CURRENT_VERSION) {
            Log.i(TAG, "Migration complete. Took " + (System.currentTimeMillis() - startTime) + " ms.");
            EventBus.getDefault().unregister(this);

            VersionTracker.updateLastSeenVersion(context);
            UI_BLOCKING_MIGRATION_RUNNING.setValue(false);
          } else if (event.getVersion() >= uiVersion) {
            Log.i(TAG, "Version is >= the UI-blocking version. Posting 'false'.");
            UI_BLOCKING_MIGRATION_RUNNING.setValue(false);
          }
        }
      });
    } else {
      Log.d(TAG, "No migrations.");
      TextSecurePreferences.setAppMigrationVersion(context, CURRENT_VERSION);
      VersionTracker.updateLastSeenVersion(context);
      UI_BLOCKING_MIGRATION_RUNNING.setValue(false);
    }
  }

  /**
   * @return A {@link LiveData} object that will update with whether or not a UI blocking migration
   * is in progress.
   */
  public static LiveData<Boolean> getUiBlockingMigrationStatus() {
    return UI_BLOCKING_MIGRATION_RUNNING;
  }

  /**
   * @return True if a UI blocking migration is running.
   */
  public static boolean isUiBlockingMigrationRunning() {
    Boolean value = UI_BLOCKING_MIGRATION_RUNNING.getValue();
    return value != null && value;
  }

  /**
   * @return Whether or not we're in the middle of an update, as determined by the last seen and
   * current version.
   */
  public static boolean isUpdate(@NonNull Context context) {
    return isLegacyUpdate(context) || TextSecurePreferences.getAppMigrationVersion(context) < CURRENT_VERSION;
  }

  private static LinkedHashMap<Integer, MigrationJob> getMigrationJobs(@NonNull Context context, int lastSeenVersion) {
    LinkedHashMap<Integer, MigrationJob> jobs = new LinkedHashMap<>();

    if (lastSeenVersion < Version.LEGACY) {
      jobs.put(Version.LEGACY, new LegacyMigrationJob());
    }

    if (lastSeenVersion < Version.RECIPIENT_ID) {
      jobs.put(Version.RECIPIENT_ID, new DatabaseMigrationJob());
    }

    if (lastSeenVersion < Version.RECIPIENT_SEARCH) {
      jobs.put(Version.RECIPIENT_SEARCH, new RecipientSearchMigrationJob());
    }

    if (lastSeenVersion < Version.RECIPIENT_CLEANUP) {
      jobs.put(Version.RECIPIENT_CLEANUP, new DatabaseMigrationJob());
    }

    if (lastSeenVersion < Version.AVATAR_MIGRATION) {
      jobs.put(Version.AVATAR_MIGRATION, new AvatarMigrationJob());
    }

    if (lastSeenVersion < Version.UUIDS) {
      jobs.put(Version.UUIDS, new UuidMigrationJob());
    }

    if (lastSeenVersion < Version.CACHED_ATTACHMENTS) {
      jobs.put(Version.CACHED_ATTACHMENTS, new CachedAttachmentsMigrationJob());
    }

    if (lastSeenVersion < Version.STICKERS_LAUNCH) {
      jobs.put(Version.STICKERS_LAUNCH, new StickerLaunchMigrationJob());
    }

    // This migration only triggered a test we aren't interested in any more.
    // if (lastSeenVersion < Version.TEST_ARGON2) {
    // jobs.put(Version.TEST_ARGON2, new Argon2TestMigrationJob());
    // }

    if (lastSeenVersion < Version.SWOON_STICKERS) {
      jobs.put(Version.SWOON_STICKERS, new StickerAdditionMigrationJob(BlessedPacks.SWOON_HANDS, BlessedPacks.SWOON_FACES));
    }

    if (lastSeenVersion < Version.STORAGE_SERVICE) {
      jobs.put(Version.STORAGE_SERVICE, new StorageServiceMigrationJob());
    }

    // Superceded by StorageCapabilityMigrationJob
//    if (lastSeenVersion < Version.STORAGE_KEY_ROTATE) {
//      jobs.put(Version.STORAGE_KEY_ROTATE, new StorageKeyRotationMigrationJob());
//    }

    if (lastSeenVersion < Version.REMOVE_AVATAR_ID) {
      jobs.put(Version.REMOVE_AVATAR_ID, new AvatarIdRemovalMigrationJob());
    }

    if (lastSeenVersion < Version.STORAGE_CAPABILITY) {
      jobs.put(Version.STORAGE_CAPABILITY, new StorageCapabilityMigrationJob());
    }

    if (lastSeenVersion < Version.PIN_REMINDER) {
      jobs.put(Version.PIN_REMINDER, new PinReminderMigrationJob());
    }

    if (lastSeenVersion < Version.VERSIONED_PROFILE) {
      jobs.put(Version.VERSIONED_PROFILE, new ProfileMigrationJob());
    }

    if (lastSeenVersion < Version.PIN_OPT_OUT) {
      jobs.put(Version.PIN_OPT_OUT, new PinOptOutMigration());
    }

    if (lastSeenVersion < Version.TRIM_SETTINGS) {
      jobs.put(Version.TRIM_SETTINGS, new TrimByLengthSettingsMigrationJob());
    }

    if (lastSeenVersion < Version.THUMBNAIL_CLEANUP) {
      jobs.put(Version.THUMBNAIL_CLEANUP, new DatabaseMigrationJob());
    }

    if (lastSeenVersion < Version.GV2) {
      jobs.put(Version.GV2, new AttributesMigrationJob());
    }

    if (lastSeenVersion < Version.GV2_2) {
      jobs.put(Version.GV2_2, new AttributesMigrationJob());
    }

    if (lastSeenVersion < Version.CDS) {
      jobs.put(Version.CDS, new DirectoryRefreshMigrationJob());
    }

    if (lastSeenVersion < Version.BACKUP_NOTIFICATION) {
      jobs.put(Version.BACKUP_NOTIFICATION, new BackupNotificationMigrationJob());
    }

    if (lastSeenVersion < Version.GV1_MIGRATION) {
      jobs.put(Version.GV1_MIGRATION, new AttributesMigrationJob());
    }

    if (lastSeenVersion < Version.USER_NOTIFICATION) {
      jobs.put(Version.USER_NOTIFICATION, new UserNotificationMigrationJob());
    }

    if (lastSeenVersion < Version.DAY_BY_DAY_STICKERS) {
      jobs.put(Version.DAY_BY_DAY_STICKERS, new StickerDayByDayMigrationJob());
    }

    if (lastSeenVersion < Version.BLOB_LOCATION) {
      jobs.put(Version.BLOB_LOCATION, new BlobStorageLocationMigrationJob());
    }

    if (lastSeenVersion < Version.SYSTEM_NAME_SPLIT) {
      jobs.put(Version.SYSTEM_NAME_SPLIT, new DirectoryRefreshMigrationJob());
    }

    if (lastSeenVersion < Version.MUTE_SYNC) {
      jobs.put(Version.MUTE_SYNC, new StorageServiceMigrationJob());
    }

    if (lastSeenVersion < Version.PROFILE_SHARING_UPDATE) {
      jobs.put(Version.PROFILE_SHARING_UPDATE, new ProfileSharingUpdateMigrationJob());
    }

    if (lastSeenVersion < Version.SMS_STORAGE_SYNC) {
      jobs.put(Version.SMS_STORAGE_SYNC, new AccountRecordMigrationJob());
    }

    if (lastSeenVersion < Version.APPLY_UNIVERSAL_EXPIRE) {
      jobs.put(Version.APPLY_UNIVERSAL_EXPIRE, new ApplyUnknownFieldsToSelfMigrationJob());
    }

    if (lastSeenVersion < Version.SENDER_KEY) {
      jobs.put(Version.SENDER_KEY, new AttributesMigrationJob());
    }

    if (lastSeenVersion < Version.SENDER_KEY_2) {
      jobs.put(Version.SENDER_KEY_2, new AttributesMigrationJob());
    }

    if (lastSeenVersion < Version.DB_AUTOINCREMENT) {
      jobs.put(Version.DB_AUTOINCREMENT, new DatabaseMigrationJob());
    }

    if (lastSeenVersion < Version.ATTACHMENT_CLEANUP) {
      jobs.put(Version.ATTACHMENT_CLEANUP, new AttachmentCleanupMigrationJob());
    }

    if (lastSeenVersion < Version.LOG_CLEANUP) {
      jobs.put(Version.LOG_CLEANUP, new DeleteDeprecatedLogsMigrationJob());
    }

    if (lastSeenVersion < Version.ATTACHMENT_CLEANUP_2) {
      jobs.put(Version.ATTACHMENT_CLEANUP_2, new AttachmentCleanupMigrationJob());
    }

    if (lastSeenVersion < Version.ANNOUNCEMENT_GROUP_CAPABILITY) {
      jobs.put(Version.ANNOUNCEMENT_GROUP_CAPABILITY, new AttributesMigrationJob());
    }

    if (lastSeenVersion < Version.STICKER_MY_DAILY_LIFE) {
      jobs.put(Version.STICKER_MY_DAILY_LIFE, new StickerMyDailyLifeMigrationJob());
    }

    if (lastSeenVersion < Version.SENDER_KEY_3) {
      jobs.put(Version.SENDER_KEY_3, new AttributesMigrationJob());
    }

    if (lastSeenVersion < Version.CHANGE_NUMBER_SYNC) {
      jobs.put(Version.CHANGE_NUMBER_SYNC, new AccountRecordMigrationJob());
    }

    if (lastSeenVersion < Version.CHANGE_NUMBER_CAPABILITY) {
      jobs.put(Version.CHANGE_NUMBER_CAPABILITY, new AttributesMigrationJob());
    }

    if (lastSeenVersion < Version.CHANGE_NUMBER_CAPABILITY_2) {
      jobs.put(Version.CHANGE_NUMBER_CAPABILITY_2, new AttributesMigrationJob());
    }

    if (lastSeenVersion < Version.DEFAULT_REACTIONS_SYNC) {
      jobs.put(Version.DEFAULT_REACTIONS_SYNC, new StorageServiceMigrationJob());
    }

    if (lastSeenVersion < Version.DB_REACTIONS_MIGRATION) {
      jobs.put(Version.DB_REACTIONS_MIGRATION, new DatabaseMigrationJob());
    }

    if (lastSeenVersion < Version.PNI) {
      jobs.put(Version.PNI, new PniMigrationJob());
    }

    return jobs;
  }

  private static boolean isLegacyUpdate(@NonNull Context context) {
    return VersionTracker.getLastSeenVersion(context) < LEGACY_CANONICAL_VERSION;
  }
}
