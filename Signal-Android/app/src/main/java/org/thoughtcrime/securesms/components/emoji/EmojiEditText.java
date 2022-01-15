package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.InputFilter;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.emoji.EmojiProvider.EmojiDrawable;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.util.TextSecurePreferences;


public class EmojiEditText extends AppCompatEditText {
  private static final String TAG = Log.tag(EmojiEditText.class);

  public EmojiEditText(Context context) {
    this(context, null);
  }

  public EmojiEditText(Context context, AttributeSet attrs) {
    this(context, attrs, R.attr.editTextStyle);
  }

  public EmojiEditText(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.EmojiTextView, 0, 0);
    boolean forceCustom = a.getBoolean(R.styleable.EmojiTextView_emoji_forceCustom, false);
    a.recycle();

    if (!isInEditMode() && (forceCustom || !SignalStore.settings().isPreferSystemEmoji())) {
      setFilters(appendEmojiFilter(this.getFilters()));
    }
  }

  public void insertEmoji(String emoji) {
    final int          start = getSelectionStart();
    final int          end   = getSelectionEnd();

    getText().replace(Math.min(start, end), Math.max(start, end), emoji);
    setSelection(start + emoji.length());
  }

  @Override
  public void invalidateDrawable(@NonNull Drawable drawable) {
    if (drawable instanceof EmojiDrawable) invalidate();
    else                                   super.invalidateDrawable(drawable);
  }

  private InputFilter[] appendEmojiFilter(@Nullable InputFilter[] originalFilters) {
    InputFilter[] result;

    if (originalFilters != null) {
      result = new InputFilter[originalFilters.length + 1];
      System.arraycopy(originalFilters, 0, result, 1, originalFilters.length);
    } else {
      result = new InputFilter[1];
    }

    result[0] = new EmojiFilter(this);

    return result;
  }
}
