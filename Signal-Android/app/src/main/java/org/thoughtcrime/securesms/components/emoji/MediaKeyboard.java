package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.InputAwareLayout.InputView;
import org.thoughtcrime.securesms.keyboard.KeyboardPage;
import org.thoughtcrime.securesms.keyboard.KeyboardPagerFragment;
import org.thoughtcrime.securesms.keyboard.emoji.search.EmojiSearchFragment;

import java.util.Objects;

public class MediaKeyboard extends FrameLayout implements InputView {

  private static final String TAG          = Log.tag(MediaKeyboard.class);
  private static final String EMOJI_SEARCH = "emoji_search_fragment";

  @Nullable private MediaKeyboardListener keyboardListener;
            private boolean               isInitialised;
            private int                   latestKeyboardHeight;
            private State                 keyboardState;
            private KeyboardPagerFragment keyboardPagerFragment;
            private FragmentManager       fragmentManager;

  public MediaKeyboard(Context context) {
    this(context, null);
  }

  public MediaKeyboard(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public void setFragmentManager(@NonNull FragmentManager fragmentManager) {
    this.fragmentManager = fragmentManager;
  }

  public void setKeyboardListener(@Nullable MediaKeyboardListener listener) {
    this.keyboardListener = listener;
  }

  @Override
  public boolean isShowing() {
    return getVisibility() == VISIBLE;
  }

  @Override
  public void show(int height, boolean immediate) {
    if (!isInitialised) initView();

    latestKeyboardHeight = height;

    ViewGroup.LayoutParams params = getLayoutParams();
    params.height = (keyboardState == State.NORMAL) ? latestKeyboardHeight : ViewGroup.LayoutParams.WRAP_CONTENT;
    Log.i(TAG, "showing emoji drawer with height " + params.height);
    setLayoutParams(params);

    show();
  }

  public void show() {
    if (!isInitialised) initView();

    setVisibility(VISIBLE);
    if (keyboardListener != null) keyboardListener.onShown();
    keyboardPagerFragment.show();
  }

  @Override
  public void hide(boolean immediate) {
    setVisibility(GONE);
    onCloseEmojiSearchInternal(false);
    if (keyboardListener != null) keyboardListener.onHidden();
    Log.i(TAG, "hide()");
    keyboardPagerFragment.hide();
  }

  public void onCloseEmojiSearch() {
    onCloseEmojiSearchInternal(true);
  }

  private void onCloseEmojiSearchInternal(boolean showAfterCommit) {
    if (keyboardState == State.NORMAL) {
      return;
    }

    keyboardState = State.NORMAL;

    Fragment emojiSearch = fragmentManager.findFragmentByTag(EMOJI_SEARCH);
    if (emojiSearch == null) {
      return;
    }

    FragmentTransaction transaction = fragmentManager.beginTransaction()
                                                     .remove(emojiSearch)
                                                     .show(keyboardPagerFragment)
                                                     .setCustomAnimations(R.anim.fade_in, R.anim.fade_out);

    if (showAfterCommit) {
      transaction.runOnCommit(() -> show(latestKeyboardHeight, false));
    }

    transaction.commitAllowingStateLoss();
  }

  public void onOpenEmojiSearch() {
    if (keyboardState == State.EMOJI_SEARCH) {
      return;
    }

    keyboardState = State.EMOJI_SEARCH;

    fragmentManager.beginTransaction()
                   .hide(keyboardPagerFragment)
                   .add(R.id.media_keyboard_fragment_container, new EmojiSearchFragment(), EMOJI_SEARCH)
                   .runOnCommit(() -> show(latestKeyboardHeight, true))
                   .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                   .commitAllowingStateLoss();
  }

  private void initView() {
    if (!isInitialised) {

      LayoutInflater.from(getContext()).inflate(R.layout.media_keyboard, this, true);

      if (fragmentManager == null) {
        FragmentActivity activity = resolveActivity(getContext());
        fragmentManager = activity.getSupportFragmentManager();
      }

      keyboardPagerFragment = new KeyboardPagerFragment();
      fragmentManager.beginTransaction()
                     .replace(R.id.media_keyboard_fragment_container, keyboardPagerFragment)
                     .commitNowAllowingStateLoss();

      keyboardState         = State.NORMAL;
      latestKeyboardHeight  = -1;
      isInitialised         = true;
    }
  }

  private static FragmentActivity resolveActivity(@Nullable Context context) {
    if (context instanceof FragmentActivity) {
      return (FragmentActivity) context;
    } else if (context instanceof ContextThemeWrapper) {
      return resolveActivity(((ContextThemeWrapper) context).getBaseContext());
    } else {
      throw new IllegalStateException("Could not locate FragmentActivity");
    }
  }

  public interface MediaKeyboardListener {
    void onShown();
    void onHidden();
    void onKeyboardChanged(@NonNull KeyboardPage page);
  }

  private enum State {
    NORMAL,
    EMOJI_SEARCH
  }
}
