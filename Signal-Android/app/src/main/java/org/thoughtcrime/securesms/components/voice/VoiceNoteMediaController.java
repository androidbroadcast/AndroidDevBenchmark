package org.thoughtcrime.securesms.components.voice;

import android.content.ComponentName;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.recipients.LiveRecipient;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.DefaultValueLiveData;
import org.thoughtcrime.securesms.util.livedata.LiveDataUtil;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.Objects;

/**
 * Encapsulates control of voice note playback from an Activity component.
 *
 * This class assumes that it will be created within the scope of Activity#onCreate
 *
 * The workhorse of this repository is the ProgressEventHandler, which will supply a
 * steady stream of update events to the set callback.
 */
public class VoiceNoteMediaController implements DefaultLifecycleObserver {

  public static final String EXTRA_THREAD_ID   = "voice.note.thread_id";
  public static final String EXTRA_MESSAGE_ID  = "voice.note.message_id";
  public static final String EXTRA_PROGRESS    = "voice.note.playhead";
  public static final String EXTRA_PLAY_SINGLE = "voice.note.play.single";

  private static final String TAG = Log.tag(VoiceNoteMediaController.class);

  private MediaBrowserCompat                            mediaBrowser;
  private AppCompatActivity                             activity;
  private ProgressEventHandler                          progressEventHandler;
  private MutableLiveData<VoiceNotePlaybackState>       voiceNotePlaybackState = new MutableLiveData<>(VoiceNotePlaybackState.NONE);
  private LiveData<Optional<VoiceNotePlayerView.State>> voiceNotePlayerViewState;
  private VoiceNoteProximityWakeLockManager             voiceNoteProximityWakeLockManager;

  private final MediaControllerCompatCallback mediaControllerCompatCallback = new MediaControllerCompatCallback();

  public VoiceNoteMediaController(@NonNull AppCompatActivity activity) {
    this.activity     = activity;
    this.mediaBrowser = new MediaBrowserCompat(activity,
                                               new ComponentName(activity, VoiceNotePlaybackService.class),
                                               new ConnectionCallback(),
                                               null);

    activity.getLifecycle().addObserver(this);

    voiceNotePlayerViewState = Transformations.switchMap(voiceNotePlaybackState, playbackState -> {
      if (playbackState.getClipType() instanceof VoiceNotePlaybackState.ClipType.Message) {
        VoiceNotePlaybackState.ClipType.Message message         = (VoiceNotePlaybackState.ClipType.Message) playbackState.getClipType();
        LiveRecipient                           sender          = Recipient.live(message.getSenderId());
        LiveRecipient                           threadRecipient = Recipient.live(message.getThreadRecipientId());
        LiveData<String>                        name            = LiveDataUtil.combineLatest(sender.getLiveDataResolved(),
                                                                                             threadRecipient.getLiveDataResolved(),
                                                                                             (s, t) -> VoiceNoteMediaItemFactory.getTitle(activity, s, t, null));

        return Transformations.map(name, displayName -> Optional.of(
            new VoiceNotePlayerView.State(
                playbackState.getUri(),
                message.getMessageId(),
                message.getThreadId(),
                !playbackState.isPlaying(),
                message.getSenderId(),
                message.getThreadRecipientId(),
                message.getMessagePosition(),
                message.getTimestamp(),
                displayName,
                playbackState.getPlayheadPositionMillis(),
                playbackState.getTrackDuration(),
                playbackState.getSpeed())));
      } else {
        return new DefaultValueLiveData<>(Optional.absent());
      }
    });
  }

  public LiveData<VoiceNotePlaybackState> getVoiceNotePlaybackState() {
    return voiceNotePlaybackState;
  }

  public LiveData<Optional<VoiceNotePlayerView.State>> getVoiceNotePlayerViewState() {
    return voiceNotePlayerViewState;
  }

  @Override
  public void onResume(@NonNull LifecycleOwner owner) {
    mediaBrowser.disconnect();
    mediaBrowser.connect();
    activity.setVolumeControlStream(AudioManager.STREAM_MUSIC);
  }

  @Override
  public void onPause(@NonNull LifecycleOwner owner) {
    clearProgressEventHandler();

    if (MediaControllerCompat.getMediaController(activity) != null) {
      MediaControllerCompat.getMediaController(activity).unregisterCallback(mediaControllerCompatCallback);
    }

    mediaBrowser.disconnect();
  }

  @Override
  public void onDestroy(@NonNull LifecycleOwner owner) {
    if (voiceNoteProximityWakeLockManager != null) {
      voiceNoteProximityWakeLockManager.unregisterCallbacksAndRelease();
      voiceNoteProximityWakeLockManager.unregisterFromLifecycle();
      voiceNoteProximityWakeLockManager = null;
    }

    activity.getLifecycle().removeObserver(this);
    activity = null;
  }

  private static boolean isPlayerActive(@NonNull PlaybackStateCompat playbackStateCompat) {
    return playbackStateCompat.getState() == PlaybackStateCompat.STATE_BUFFERING ||
           playbackStateCompat.getState() == PlaybackStateCompat.STATE_PLAYING;
  }

  private static boolean isPlayerPaused(@NonNull PlaybackStateCompat playbackStateCompat) {
    return playbackStateCompat.getState() == PlaybackStateCompat.STATE_PAUSED;
  }

  private static boolean isPlayerStopped(@NonNull PlaybackStateCompat playbackStateCompat) {
    return playbackStateCompat.getState() <= PlaybackStateCompat.STATE_STOPPED;
  }

  private @NonNull MediaControllerCompat getMediaController() {
    return MediaControllerCompat.getMediaController(activity);
  }


  public void startConsecutivePlayback(@NonNull Uri audioSlideUri, long messageId, double progress) {
    startPlayback(audioSlideUri, messageId, -1, progress, false);
  }

  public void startSinglePlayback(@NonNull Uri audioSlideUri, long messageId, double progress) {
    startPlayback(audioSlideUri, messageId, -1, progress, true);
  }

  public void startSinglePlaybackForDraft(@NonNull Uri draftUri, long threadId, double progress) {
    startPlayback(draftUri, -1, threadId, progress, true);
  }

  /**
   * Tells the Media service to begin playback of a given audio slide. If the audio
   * slide is currently playing, we jump to the desired position and then begin playback.
   *
   * @param audioSlideUri  The Uri of the desired audio slide
   * @param messageId      The Message id of the given audio slide
   * @param progress       The desired progress % to seek to.
   * @param singlePlayback The player will only play back the specified Uri, and not build a playlist.
   */
  private void startPlayback(@NonNull Uri audioSlideUri, long messageId, long threadId, double progress, boolean singlePlayback) {
    if (isCurrentTrack(audioSlideUri)) {
      long duration = getMediaController().getMetadata().getLong(MediaMetadataCompat.METADATA_KEY_DURATION);

      getMediaController().getTransportControls().seekTo((long) (duration * progress));
      getMediaController().getTransportControls().play();
    } else {
      Bundle extras = new Bundle();
      extras.putLong(EXTRA_MESSAGE_ID, messageId);
      extras.putLong(EXTRA_THREAD_ID, threadId);
      extras.putDouble(EXTRA_PROGRESS, progress);
      extras.putBoolean(EXTRA_PLAY_SINGLE, singlePlayback);

      getMediaController().getTransportControls().playFromUri(audioSlideUri, extras);
    }
  }

  /**
   * Pauses playback if the given audio slide is playing.
   *
   * @param audioSlideUri The Uri of the audio slide to pause.
   */
  public void pausePlayback(@NonNull Uri audioSlideUri) {
    if (isCurrentTrack(audioSlideUri)) {
      getMediaController().getTransportControls().pause();
    }
  }

  /**
   * Seeks to a given position if th given audio slide is playing. This call
   * is ignored if the given audio slide is not currently playing.
   *
   * @param audioSlideUri The Uri of the audio slide to seek.
   * @param progress      The progress percentage to seek to.
   */
  public void seekToPosition(@NonNull Uri audioSlideUri, double progress) {
    if (isCurrentTrack(audioSlideUri)) {
      long duration = getMediaController().getMetadata().getLong(MediaMetadataCompat.METADATA_KEY_DURATION);

      getMediaController().getTransportControls().pause();
      getMediaController().getTransportControls().seekTo((long) (duration * progress));
      getMediaController().getTransportControls().play();
    }
  }

  /**
   * Stops playback if the given audio slide is playing
   *
   * @param audioSlideUri The Uri of the audio slide to stop
   */
  public void stopPlaybackAndReset(@NonNull Uri audioSlideUri) {
    if (isCurrentTrack(audioSlideUri)) {
      getMediaController().getTransportControls().stop();
    }
  }

  public void setPlaybackSpeed(@NonNull Uri audioSlideUri, float playbackSpeed) {
    if (isCurrentTrack(audioSlideUri)) {
      Bundle bundle = new Bundle();
      bundle.putFloat(VoiceNotePlaybackService.ACTION_NEXT_PLAYBACK_SPEED, playbackSpeed);

      getMediaController().sendCommand(VoiceNotePlaybackService.ACTION_NEXT_PLAYBACK_SPEED, bundle, null);
    }
  }

  private boolean isCurrentTrack(@NonNull Uri uri) {
    MediaMetadataCompat metadataCompat = getMediaController().getMetadata();

    return metadataCompat != null && Objects.equals(metadataCompat.getDescription().getMediaUri(), uri);
  }

  private void notifyProgressEventHandler() {
    if (progressEventHandler == null && activity != null) {
      progressEventHandler = new ProgressEventHandler(getMediaController(), voiceNotePlaybackState);
      progressEventHandler.sendEmptyMessage(0);
    }
  }

  private void clearProgressEventHandler() {
    if (progressEventHandler != null) {
      progressEventHandler = null;
    }
  }

  private final class ConnectionCallback extends MediaBrowserCompat.ConnectionCallback {
    @Override
    public void onConnected() {
      MediaSessionCompat.Token token           = mediaBrowser.getSessionToken();
      MediaControllerCompat    mediaController = new MediaControllerCompat(activity, token);

      MediaControllerCompat.setMediaController(activity, mediaController);

      MediaMetadataCompat mediaMetadataCompat = mediaController.getMetadata();
      if (canExtractPlaybackInformationFromMetadata(mediaMetadataCompat)) {
        VoiceNotePlaybackState newState = extractStateFromMetadata(mediaController, mediaMetadataCompat, null);

        if (newState != null) {
          voiceNotePlaybackState.postValue(newState);
        } else {
          voiceNotePlaybackState.postValue(VoiceNotePlaybackState.NONE);
        }
      }

      cleanUpOldProximityWakeLockManager();
      voiceNoteProximityWakeLockManager = new VoiceNoteProximityWakeLockManager(activity, mediaController);

      mediaController.registerCallback(mediaControllerCompatCallback);

      mediaControllerCompatCallback.onPlaybackStateChanged(mediaController.getPlaybackState());
    }

    @Override
    public void onConnectionSuspended() {
      Log.d(TAG, "Voice note MediaBrowser connection suspended.");
      cleanUpOldProximityWakeLockManager();
    }

    @Override
    public void onConnectionFailed() {
      Log.d(TAG, "Voice note MediaBrowser connection failed.");
      cleanUpOldProximityWakeLockManager();
    }

    private void cleanUpOldProximityWakeLockManager() {
      if (voiceNoteProximityWakeLockManager != null) {
        Log.d(TAG, "Session reconnected, cleaning up old wake lock manager");
        voiceNoteProximityWakeLockManager.unregisterCallbacksAndRelease();
        voiceNoteProximityWakeLockManager.unregisterFromLifecycle();
        voiceNoteProximityWakeLockManager = null;
      }
    }
  }

  private static boolean canExtractPlaybackInformationFromMetadata(@Nullable MediaMetadataCompat mediaMetadataCompat) {
    return mediaMetadataCompat != null                        &&
           mediaMetadataCompat.getDescription() != null       &&
           mediaMetadataCompat.getDescription().getMediaUri() != null;
  }

  private static @Nullable VoiceNotePlaybackState extractStateFromMetadata(@NonNull MediaControllerCompat mediaController,
                                                                           @NonNull MediaMetadataCompat mediaMetadataCompat,
                                                                           @Nullable VoiceNotePlaybackState previousState)
  {
    Uri     mediaUri  = Objects.requireNonNull(mediaMetadataCompat.getDescription().getMediaUri());
    boolean autoReset = Objects.equals(mediaUri, VoiceNoteMediaItemFactory.NEXT_URI) || Objects.equals(mediaUri, VoiceNoteMediaItemFactory.END_URI);
    long    position  = mediaController.getPlaybackState().getPosition();
    long    duration  = mediaMetadataCompat.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
    Bundle  extras    = mediaController.getExtras();
    float   speed     = extras != null ? extras.getFloat(VoiceNotePlaybackService.ACTION_NEXT_PLAYBACK_SPEED, 1f) : 1f;

    if (previousState != null && Objects.equals(mediaUri, previousState.getUri())) {
      if (position < 0 && previousState.getPlayheadPositionMillis() >= 0) {
        position = previousState.getPlayheadPositionMillis();
      }

      if (duration <= 0 && previousState.getTrackDuration() > 0) {
        duration = previousState.getTrackDuration();
      }
    }

    if (duration > 0 && position >= 0 && position <= duration) {
      return new VoiceNotePlaybackState(mediaUri,
                                        position,
                                        duration,
                                        autoReset,
                                        speed,
                                        isPlayerActive(mediaController.getPlaybackState()),
                                        getClipType(mediaMetadataCompat.getBundle()));
    } else {
      return null;
    }
  }

  private static @Nullable VoiceNotePlaybackState constructPlaybackState(@NonNull MediaControllerCompat mediaController,
                                                                         @Nullable VoiceNotePlaybackState previousState)
  {
    MediaMetadataCompat mediaMetadataCompat = mediaController.getMetadata();
    if (isPlayerActive(mediaController.getPlaybackState()) &&
        canExtractPlaybackInformationFromMetadata(mediaMetadataCompat))
    {
      return extractStateFromMetadata(mediaController, mediaMetadataCompat, previousState);
    } else if (isPlayerPaused(mediaController.getPlaybackState()) &&
               mediaMetadataCompat != null)
    {
      long position = mediaController.getPlaybackState().getPosition();
      long duration = mediaMetadataCompat.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);

      if (previousState != null && position < duration) {
        return previousState.asPaused();
      } else {
        return VoiceNotePlaybackState.NONE;
      }
    } else {
      return VoiceNotePlaybackState.NONE;
    }
  }

  private static @NonNull VoiceNotePlaybackState.ClipType getClipType(@Nullable Bundle mediaExtras) {
    long        messageId         = -1L;
    RecipientId senderId          = RecipientId.UNKNOWN;
    long        messagePosition   = -1L;
    long        threadId          = -1L;
    RecipientId threadRecipientId = RecipientId.UNKNOWN;
    long        timestamp         = -1L;

    if (mediaExtras != null) {
      messageId       = mediaExtras.getLong(VoiceNoteMediaItemFactory.EXTRA_MESSAGE_ID, -1L);
      messagePosition = mediaExtras.getLong(VoiceNoteMediaItemFactory.EXTRA_MESSAGE_POSITION, -1L);
      threadId        = mediaExtras.getLong(VoiceNoteMediaItemFactory.EXTRA_THREAD_ID, -1L);
      timestamp       = mediaExtras.getLong(VoiceNoteMediaItemFactory.EXTRA_MESSAGE_TIMESTAMP, -1L);

      String serializedSenderId = mediaExtras.getString(VoiceNoteMediaItemFactory.EXTRA_INDIVIDUAL_RECIPIENT_ID);
      if (serializedSenderId != null) {
        senderId = RecipientId.from(serializedSenderId);
      }

      String serializedThreadRecipientId = mediaExtras.getString(VoiceNoteMediaItemFactory.EXTRA_THREAD_RECIPIENT_ID);
      if (serializedThreadRecipientId != null) {
        threadRecipientId = RecipientId.from(serializedThreadRecipientId);
      }
    }

    if (messageId != -1L) {
      return new VoiceNotePlaybackState.ClipType.Message(messageId,
                                                         senderId,
                                                         threadRecipientId,
                                                         messagePosition,
                                                         threadId,
                                                         timestamp);
    } else {
      return VoiceNotePlaybackState.ClipType.Draft.INSTANCE;
    }
  }

  private static class ProgressEventHandler extends Handler {

    private final MediaControllerCompat                   mediaController;
    private final MutableLiveData<VoiceNotePlaybackState> voiceNotePlaybackState;

    private ProgressEventHandler(@NonNull MediaControllerCompat mediaController,
                                 @NonNull MutableLiveData<VoiceNotePlaybackState> voiceNotePlaybackState)
    {
      super(Looper.getMainLooper());

      this.mediaController        = mediaController;
      this.voiceNotePlaybackState = voiceNotePlaybackState;
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
      VoiceNotePlaybackState newPlaybackState = constructPlaybackState(mediaController, voiceNotePlaybackState.getValue());

      if (newPlaybackState != null) {
        voiceNotePlaybackState.postValue(newPlaybackState);
      }

      if (isPlayerActive(mediaController.getPlaybackState())) {
        sendEmptyMessageDelayed(0, 50);
      }
    }
  }

  private final class MediaControllerCompatCallback extends MediaControllerCompat.Callback {
    @Override
    public void onPlaybackStateChanged(PlaybackStateCompat state) {
      if (isPlayerActive(state)) {
        notifyProgressEventHandler();
      } else {
        clearProgressEventHandler();

        if (isPlayerStopped(state)) {
          voiceNotePlaybackState.postValue(VoiceNotePlaybackState.NONE);
        }
      }
    }
  }
}
