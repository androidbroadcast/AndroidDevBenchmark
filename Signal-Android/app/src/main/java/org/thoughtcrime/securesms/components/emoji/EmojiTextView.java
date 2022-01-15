package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Annotation;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.text.method.TransformationMethod;
import android.text.style.MetricAffectingSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ViewGroup;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.emoji.parsing.EmojiParser;
import org.thoughtcrime.securesms.components.mention.MentionAnnotation;
import org.thoughtcrime.securesms.components.mention.MentionRendererDelegate;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.List;


public class EmojiTextView extends AppCompatTextView {

  private final boolean scaleEmojis;

  private static final char ELLIPSIS = '…';

  private boolean                forceCustom;
  private CharSequence           previousText;
  private BufferType             previousBufferType;
  private TransformationMethod   previousTransformationMethod;
  private float                  originalFontSize;
  private boolean                useSystemEmoji;
  private boolean                sizeChangeInProgress;
  private int                    maxLength;
  private CharSequence           overflowText;
  private CharSequence           previousOverflowText;
  private boolean                renderMentions;
  private boolean                measureLastLine;
  private int                    lastLineWidth = -1;
  private TextDirectionHeuristic textDirection;
  private boolean                isJumbomoji;

  private MentionRendererDelegate mentionRendererDelegate;

  public EmojiTextView(Context context) {
    this(context, null);
  }

  public EmojiTextView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public EmojiTextView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.EmojiTextView, 0, 0);
    scaleEmojis     = a.getBoolean(R.styleable.EmojiTextView_scaleEmojis, false);
    maxLength       = a.getInteger(R.styleable.EmojiTextView_emoji_maxLength, -1);
    forceCustom     = a.getBoolean(R.styleable.EmojiTextView_emoji_forceCustom, false);
    renderMentions  = a.getBoolean(R.styleable.EmojiTextView_emoji_renderMentions, true);
    measureLastLine = a.getBoolean(R.styleable.EmojiTextView_measureLastLine, false);
    a.recycle();

    a = context.obtainStyledAttributes(attrs, new int[]{android.R.attr.textSize});
    originalFontSize = a.getDimensionPixelSize(0, 0);
    a.recycle();

    if (renderMentions) {
      mentionRendererDelegate = new MentionRendererDelegate(getContext(), ContextCompat.getColor(getContext(), R.color.transparent_black_20));
    }

    textDirection = getLayoutDirection() == LAYOUT_DIRECTION_LTR ? TextDirectionHeuristics.FIRSTSTRONG_RTL : TextDirectionHeuristics.ANYRTL_LTR;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (renderMentions && getText() instanceof Spanned && getLayout() != null) {
      int checkpoint = canvas.save();
      canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop());
      try {
        mentionRendererDelegate.draw(canvas, (Spanned) getText(), getLayout());
      } finally {
        canvas.restoreToCount(checkpoint);
      }
    }
    super.onDraw(canvas);
  }

  @Override
  public void setText(@Nullable CharSequence text, BufferType type) {
    EmojiParser.CandidateList candidates = isInEditMode() ? null : EmojiProvider.getCandidates(text);

    if (scaleEmojis && candidates != null && candidates.allEmojis) {
      int   emojis = candidates.size();
      float scale  = 1.0f;

      if (emojis <= 8) scale += 0.25f;
      if (emojis <= 6) scale += 0.25f;
      if (emojis <= 4) scale += 0.25f;
      if (emojis <= 2) scale += 0.25f;

      isJumbomoji = scale > 1.0f;
      super.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalFontSize * scale);
    } else if (scaleEmojis) {
      isJumbomoji = false;
      super.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalFontSize);
    }

    if (unchanged(text, overflowText, type)) {
      return;
    }

    previousText                 = text;
    previousOverflowText         = overflowText;
    previousBufferType           = type;
    useSystemEmoji               = useSystemEmoji();
    previousTransformationMethod = getTransformationMethod();

    if (useSystemEmoji || candidates == null || candidates.size() == 0) {
      super.setText(new SpannableStringBuilder(Optional.fromNullable(text).or("")), BufferType.SPANNABLE);
    } else {
      CharSequence emojified = EmojiProvider.emojify(candidates, text, this);
      super.setText(new SpannableStringBuilder(emojified), BufferType.SPANNABLE);
    }

    // Android fails to ellipsize spannable strings. (https://issuetracker.google.com/issues/36991688)
    // We ellipsize them ourselves by manually truncating the appropriate section.
    if (getText() != null && getText().length() > 0 && getEllipsize() == TextUtils.TruncateAt.END) {
      if (maxLength > 0) {
        ellipsizeAnyTextForMaxLength();
      } else if (getMaxLines() > 0) {
        ellipsizeEmojiTextForMaxLines();
      }
    }

    if (getLayoutParams() != null && getLayoutParams().width == ViewGroup.LayoutParams.WRAP_CONTENT) {
      requestLayout();
    }
  }

  @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    widthMeasureSpec = applyWidthMeasureRoundingFix(widthMeasureSpec);

    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    CharSequence text = getText();
    if (getLayout() == null || !measureLastLine || text == null || text.length() == 0) {
      lastLineWidth = -1;
    } else {
      Layout layout = getLayout();
      text = layout.getText();

      int lines = layout.getLineCount();
      int start = layout.getLineStart(lines - 1);

      if ((getLayoutDirection() == LAYOUT_DIRECTION_LTR && textDirection.isRtl(text, 0, text.length())) ||
          (getLayoutDirection() == LAYOUT_DIRECTION_RTL && !textDirection.isRtl(text, 0, text.length()))) {
        lastLineWidth = getMeasuredWidth();
      } else {
        lastLineWidth = (int) getPaint().measureText(text, start, text.length());
      }
    }
  }

  /**
   * Starting from API 30, there can be a rounding error in text layout when a non-default font
   * scale is used. This causes a line break to be inserted where there shouldn't be one. Force the
   * width to be larger to work around this problem.
   * https://issuetracker.google.com/issues/173574230
   *
   * @param widthMeasureSpec the original measure spec passed to {@link #onMeasure(int, int)}
   * @return the measure spec with the workaround, or the original one.
   */
  private int applyWidthMeasureRoundingFix(int widthMeasureSpec) {
    if (Build.VERSION.SDK_INT >= 30 && Math.abs(getResources().getConfiguration().fontScale - 1f) > 0.01f) {
      CharSequence text = getText();
      if (text != null) {
        int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);

        float measuredTextWidth = hasMetricAffectingSpan(text) ? Layout.getDesiredWidth(text, getPaint()) : getPaint().measureText(text, 0, text.length());
        int   desiredWidth      = (int) measuredTextWidth + getPaddingLeft() + getPaddingRight();

        if (widthSpecMode == MeasureSpec.AT_MOST && desiredWidth < widthSpecSize) {
          return MeasureSpec.makeMeasureSpec(desiredWidth + 1, MeasureSpec.EXACTLY);
        }
      }
    }

    return widthMeasureSpec;
  }

  private boolean hasMetricAffectingSpan(@NonNull CharSequence text) {
    if (!(text instanceof Spanned)) {
      return false;
    }

    return ((Spanned) text).nextSpanTransition(-1, text.length(), MetricAffectingSpan.class) != text.length();
  }

  public int getLastLineWidth() {
    return lastLineWidth;
  }

  public boolean isSingleLine() {
    return getLayout() != null && getLayout().getLineCount() == 1;
  }

  public boolean isJumbomoji() {
    return isJumbomoji;
  }

  public void setOverflowText(@Nullable CharSequence overflowText) {
    this.overflowText = overflowText;
    setText(previousText, BufferType.SPANNABLE);
  }

  public void setForceCustomEmoji(boolean forceCustom) {
    if (this.forceCustom != forceCustom) {
      this.forceCustom = forceCustom;
      setText(previousText, BufferType.SPANNABLE);
    }
  }

  private void ellipsizeAnyTextForMaxLength() {
    if (maxLength > 0 && getText().length() > maxLength + 1) {
      SpannableStringBuilder newContent = new SpannableStringBuilder();

      CharSequence shortenedText = getText().subSequence(0, maxLength);
      if (shortenedText instanceof Spanned) {
        Spanned          spanned            = (Spanned) shortenedText;
        List<Annotation> mentionAnnotations = MentionAnnotation.getMentionAnnotations(spanned, maxLength - 1, maxLength);
        if (!mentionAnnotations.isEmpty()) {
          shortenedText = shortenedText.subSequence(0, spanned.getSpanStart(mentionAnnotations.get(0)));
        }
      }

      newContent.append(shortenedText)
                .append(ELLIPSIS)
                .append(Util.emptyIfNull(overflowText));

      EmojiParser.CandidateList newCandidates = isInEditMode() ? null : EmojiProvider.getCandidates(newContent);

      if (useSystemEmoji || newCandidates == null || newCandidates.size() == 0) {
        super.setText(newContent, BufferType.SPANNABLE);
      } else {
        CharSequence emojified = EmojiProvider.emojify(newCandidates, newContent, this);
        super.setText(emojified, BufferType.SPANNABLE);
      }
    }
  }

  private void ellipsizeEmojiTextForMaxLines() {
    post(() -> {
      if (getLayout() == null) {
        ellipsizeEmojiTextForMaxLines();
        return;
      }

      int maxLines = TextViewCompat.getMaxLines(EmojiTextView.this);
      if (maxLines <= 0 && maxLength < 0) {
        return;
      }

      int lineCount = getLineCount();
      if (lineCount > maxLines) {
        int overflowStart = getLayout().getLineStart(maxLines - 1);
        CharSequence overflow = getText().subSequence(overflowStart, getText().length());
        float adjust = overflowText != null ? getPaint().measureText(overflowText, 0, overflowText.length()) : 0f;
        CharSequence ellipsized = TextUtils.ellipsize(overflow, getPaint(), getWidth() - adjust, TextUtils.TruncateAt.END);

        SpannableStringBuilder newContent = new SpannableStringBuilder();
        newContent.append(getText().subSequence(0, overflowStart))
                  .append(ellipsized.subSequence(0, ellipsized.length()))
                  .append(Optional.fromNullable(overflowText).or(""));

        EmojiParser.CandidateList newCandidates = isInEditMode() ? null : EmojiProvider.getCandidates(newContent);
        CharSequence              emojified     = EmojiProvider.emojify(newCandidates, newContent, this);

        super.setText(emojified, BufferType.SPANNABLE);
      }
    });
  }

  private boolean unchanged(CharSequence text, CharSequence overflowText, BufferType bufferType) {
    return Util.equals(previousText, text)                 &&
           Util.equals(previousOverflowText, overflowText) &&
           Util.equals(previousBufferType, bufferType)     &&
           useSystemEmoji == useSystemEmoji()              &&
           !sizeChangeInProgress                           &&
           previousTransformationMethod == getTransformationMethod();
  }

  private boolean useSystemEmoji() {
   return !forceCustom && SignalStore.settings().isPreferSystemEmoji();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);

    if (!sizeChangeInProgress) {
      sizeChangeInProgress = true;
      setText(previousText, previousBufferType);
      sizeChangeInProgress = false;
    }
  }

  @Override
  public void invalidateDrawable(@NonNull Drawable drawable) {
    if (drawable instanceof EmojiProvider.EmojiDrawable) invalidate();
    else                                                 super.invalidateDrawable(drawable);
  }

  @Override
  public void setTextSize(float size) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
  }

  @Override
  public void setTextSize(int unit, float size) {
    this.originalFontSize = TypedValue.applyDimension(unit, size, getResources().getDisplayMetrics());
    super.setTextSize(unit, size);
  }

  public void setMentionBackgroundTint(@ColorInt int mentionBackgroundTint) {
    if (renderMentions) {
      mentionRendererDelegate.setTint(mentionBackgroundTint);
    }
  }
}
