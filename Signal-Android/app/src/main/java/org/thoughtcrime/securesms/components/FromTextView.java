package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.CharacterStyle;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.emoji.SimpleEmojiTextView;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.SpanUtil;
import org.thoughtcrime.securesms.util.ViewUtil;

import java.util.Objects;

public class FromTextView extends SimpleEmojiTextView {

  private static final String TAG = Log.tag(FromTextView.class);

  public FromTextView(Context context) {
    super(context);
  }

  public FromTextView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public void setText(Recipient recipient) {
    setText(recipient, true);
  }

  public void setText(Recipient recipient, boolean read) {
    setText(recipient, read, null);
  }

  public void setText(Recipient recipient, boolean read, @Nullable String suffix) {
    setText(recipient, recipient.getDisplayName(getContext()), read, suffix);
  }

  public void setText(Recipient recipient, @Nullable CharSequence fromString, boolean read, @Nullable String suffix) {
    SpannableStringBuilder builder  = new SpannableStringBuilder();
    SpannableString        fromSpan = new SpannableString(fromString);
    fromSpan.setSpan(getFontSpan(!read), 0, fromSpan.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);

    if (recipient.isSelf()) {
      builder.append(getContext().getString(R.string.note_to_self));
    } else {
      builder.append(fromSpan);
    }

    if (suffix != null) {
      builder.append(suffix);
    }

    setText(builder);

    if      (recipient.isBlocked()) setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_block_grey600_18dp, 0, 0, 0);
    else if (recipient.isMuted())   setCompoundDrawablesRelativeWithIntrinsicBounds(getMuted(), null, null, null);
    else                            setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
  }

  private Drawable getMuted() {
    Drawable mutedDrawable = Objects.requireNonNull(ContextCompat.getDrawable(getContext(), R.drawable.ic_bell_disabled_16));

    mutedDrawable.setBounds(0, 0, ViewUtil.dpToPx(18), ViewUtil.dpToPx(18));
    mutedDrawable.setColorFilter(new PorterDuffColorFilter(ContextCompat.getColor(getContext(), R.color.signal_icon_tint_secondary), PorterDuff.Mode.SRC_IN));

    return mutedDrawable;
  }

  private CharacterStyle getFontSpan(boolean isBold) {
    return isBold ? SpanUtil.getBoldSpan() : SpanUtil.getNormalSpan();
  }
}
