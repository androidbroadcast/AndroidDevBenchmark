package org.thoughtcrime.securesms.components.settings

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.SpanUtil

sealed class DSLSettingsText {

  protected abstract val modifiers: List<Modifier>

  private data class FromResource(
    @StringRes private val stringId: Int,
    override val modifiers: List<Modifier>
  ) : DSLSettingsText() {
    override fun getCharSequence(context: Context): CharSequence {
      return context.getString(stringId)
    }
  }

  private data class FromCharSequence(
    private val charSequence: CharSequence,
    override val modifiers: List<Modifier>
  ) : DSLSettingsText() {
    override fun getCharSequence(context: Context): CharSequence = charSequence
  }

  protected abstract fun getCharSequence(context: Context): CharSequence

  fun resolve(context: Context): CharSequence {
    val text: CharSequence = getCharSequence(context)
    return modifiers.fold(text) { t, m -> m.modify(context, t) }
  }

  companion object {
    fun from(@StringRes stringId: Int, @ColorInt textColor: Int): DSLSettingsText =
      FromResource(stringId, listOf(ColorModifier(textColor)))

    fun from(@StringRes stringId: Int, vararg modifiers: Modifier): DSLSettingsText =
      FromResource(stringId, modifiers.toList())

    fun from(charSequence: CharSequence, vararg modifiers: Modifier): DSLSettingsText =
      FromCharSequence(charSequence, modifiers.toList())
  }

  interface Modifier {
    fun modify(context: Context, charSequence: CharSequence): CharSequence
  }

  class ColorModifier(@ColorInt private val textColor: Int) : Modifier {
    override fun modify(context: Context, charSequence: CharSequence): CharSequence {
      return SpanUtil.color(textColor, charSequence)
    }

    override fun equals(other: Any?): Boolean = textColor == (other as? ColorModifier)?.textColor
    override fun hashCode(): Int = textColor
  }

  object CenterModifier : Modifier {
    override fun modify(context: Context, charSequence: CharSequence): CharSequence {
      return SpanUtil.center(charSequence)
    }
  }

  object Title2BoldModifier : TextAppearanceModifier(R.style.TextAppearance_Signal_Title2_Bold)
  object Body1BoldModifier : TextAppearanceModifier(R.style.TextAppearance_Signal_Body1_Bold)

  open class TextAppearanceModifier(@StyleRes private val textAppearance: Int) : Modifier {
    override fun modify(context: Context, charSequence: CharSequence): CharSequence {
      return SpanUtil.textAppearance(context, textAppearance, charSequence)
    }

    override fun equals(other: Any?): Boolean = textAppearance == (other as? TextAppearanceModifier)?.textAppearance
    override fun hashCode(): Int = textAppearance
  }

  object BoldModifier : Modifier {
    override fun modify(context: Context, charSequence: CharSequence): CharSequence {
      return SpanUtil.bold(charSequence)
    }
  }
}
