package org.thoughtcrime.securesms.conversation.colors

import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import androidx.annotation.ColorInt
import com.google.common.base.Objects
import org.signal.core.util.ColorUtil
import org.thoughtcrime.securesms.components.RotatableGradientDrawable
import org.thoughtcrime.securesms.database.model.databaseprotos.ChatColor
import org.thoughtcrime.securesms.util.customizeOnDraw
import kotlin.math.min

/**
 * ChatColors represent how to render the avatar and bubbles in a given context.
 *
 * @param id             The identifier for this chat color. It is either BuiltIn, NotSet, or Custom(long)
 * @param linearGradient The LinearGradient to render. Null if this is for a single color.
 * @param singleColor    The single color to render. Null if this is for a linear gradient.
 */
class ChatColors private constructor(
  val id: Id,
  private val linearGradient: LinearGradient?,
  private val singleColor: Int?
) {

  fun isGradient(): Boolean = Build.VERSION.SDK_INT >= 21 && linearGradient != null

  /**
   * Returns the Drawable to render the linear gradient, or null if this ChatColors is a single color.
   */
  val chatBubbleMask: Drawable
    get() {
      return when {
        Build.VERSION.SDK_INT < 21 -> {
          ColorDrawable(Color.TRANSPARENT)
        }
        linearGradient != null -> {
          RotatableGradientDrawable(
            linearGradient.degrees,
            linearGradient.colors,
            linearGradient.positions
          )
        }
        else -> {
          ColorDrawable(asSingleColor())
        }
      }
    }

  /**
   * Returns the ColorFilter to apply to a conversation bubble or other relevant piece of UI.
   */
  val chatBubbleColorFilter: ColorFilter = if (Build.VERSION.SDK_INT >= 21) {
    PorterDuffColorFilter(Color.TRANSPARENT, PorterDuff.Mode.SRC_IN)
  } else {
    PorterDuffColorFilter(asSingleColor(), PorterDuff.Mode.SRC_IN)
  }

  @ColorInt
  fun asSingleColor(): Int {
    if (singleColor != null) {
      return singleColor
    }

    if (linearGradient != null) {
      val start = linearGradient.colors.first()
      val end = linearGradient.colors.last()

      return ColorUtil.blendARGB(start, end, 0.5f)
    }

    throw AssertionError()
  }

  fun serialize(): ChatColor {
    val builder: ChatColor.Builder = ChatColor.newBuilder()

    if (linearGradient != null) {
      val gradientBuilder = ChatColor.LinearGradient.newBuilder()

      gradientBuilder.rotation = linearGradient.degrees
      linearGradient.colors.forEach { gradientBuilder.addColors(it) }
      linearGradient.positions.forEach { gradientBuilder.addPositions(it) }

      builder.setLinearGradient(gradientBuilder)
    }

    if (singleColor != null) {
      builder.setSingleColor(ChatColor.SingleColor.newBuilder().setColor(singleColor))
    }

    return builder.build()
  }

  fun getColors(): IntArray {
    return linearGradient?.colors ?: if (singleColor != null) {
      intArrayOf(singleColor)
    } else {
      throw AssertionError()
    }
  }

  fun getDegrees(): Float {
    return linearGradient?.degrees ?: 180f
  }

  fun asCircle(): Drawable {
    if (Build.VERSION.SDK_INT < 21) {
      return ShapeDrawable(OvalShape()).apply {
        paint.color = asSingleColor()
      }
    }

    val toWrap: Drawable = chatBubbleMask
    val path = Path()

    return toWrap.customizeOnDraw { wrapped, canvas ->
      canvas.save()
      path.rewind()
      path.addCircle(
        wrapped.bounds.exactCenterX(),
        wrapped.bounds.exactCenterY(),
        min(wrapped.bounds.exactCenterX(), wrapped.bounds.exactCenterY()),
        Path.Direction.CW
      )
      canvas.clipPath(path)
      wrapped.draw(canvas)
      canvas.restore()
    }
  }

  fun withId(id: Id): ChatColors = ChatColors(id, linearGradient, singleColor)

  override fun equals(other: Any?): Boolean {
    val otherChatColors: ChatColors = (other as? ChatColors) ?: return false

    if (id != otherChatColors.id) return false
    if (linearGradient != otherChatColors.linearGradient) return false
    if (singleColor != otherChatColors.singleColor) return false

    return true
  }

  override fun hashCode(): Int {
    return Objects.hashCode(linearGradient, singleColor, id)
  }

  companion object {
    @JvmStatic
    fun forChatColor(id: Id, chatColor: ChatColor): ChatColors {
      assert(chatColor.hasSingleColor() xor chatColor.hasLinearGradient())

      return if (chatColor.hasLinearGradient()) {
        val linearGradient = LinearGradient(
          chatColor.linearGradient.rotation,
          chatColor.linearGradient.colorsList.toIntArray(),
          chatColor.linearGradient.positionsList.toFloatArray()
        )

        forGradient(id, linearGradient)
      } else {
        val singleColor = chatColor.singleColor.color

        forColor(id, singleColor)
      }
    }

    @JvmStatic
    fun forGradient(id: Id, linearGradient: LinearGradient): ChatColors =
      ChatColors(id, linearGradient, null)

    @JvmStatic
    fun forColor(id: Id, @ColorInt color: Int): ChatColors =
      ChatColors(id, null, color)
  }

  sealed class Id(val longValue: Long) {
    /**
     * Represents user selection of 'auto'.
     */
    object Auto : Id(-2)

    /**
     * Represents a built in color.
     */
    object BuiltIn : Id(-1)

    /**
     * Represents an unsaved or un-set option.
     */
    object NotSet : Id(0)

    /**
     * Represents a custom created ChatColors.
     */
    class Custom internal constructor(id: Long) : Id(id)

    override fun equals(other: Any?): Boolean {
      return longValue == (other as? Id)?.longValue
    }

    override fun hashCode(): Int {
      return Objects.hashCode(longValue)
    }

    companion object {
      @JvmStatic
      fun forLongValue(longValue: Long): Id {
        return when (longValue) {
          -2L -> Auto
          -1L -> BuiltIn
          0L -> NotSet
          else -> Custom(longValue)
        }
      }
    }
  }

  data class LinearGradient(
    val degrees: Float,
    val colors: IntArray,
    val positions: FloatArray
  ) {

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as LinearGradient

      if (!colors.contentEquals(other.colors)) return false
      if (!positions.contentEquals(other.positions)) return false

      return true
    }

    override fun hashCode(): Int {
      var result = colors.contentHashCode()
      result = 31 * result + positions.contentHashCode()
      return result
    }
  }
}
