package org.thoughtcrime.securesms.mediasend;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.mms.SentMediaQuality;
import org.whispersystems.libsignal.util.guava.Optional;

public final class VideoTrimTransform implements MediaTransform {

  private final VideoEditorFragment.Data data;

  public VideoTrimTransform(@NonNull VideoEditorFragment.Data data) {
    this.data = data;
  }

  @WorkerThread
  @Override
  public @NonNull Media transform(@NonNull Context context, @NonNull Media media) {
    return new Media(media.getUri(),
                     media.getMimeType(),
                     media.getDate(),
                     media.getWidth(),
                     media.getHeight(),
                     media.getSize(),
                     media.getDuration(),
                     media.isBorderless(),
                     media.isVideoGif(),
                     media.getBucketId(),
                     media.getCaption(),
                     Optional.of(new AttachmentDatabase.TransformProperties(false, data.durationEdited, data.startTimeUs, data.endTimeUs, SentMediaQuality.STANDARD.getCode())));
  }
}
