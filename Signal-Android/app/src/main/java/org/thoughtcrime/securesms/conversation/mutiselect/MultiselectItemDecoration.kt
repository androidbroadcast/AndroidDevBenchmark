package org.thoughtcrime.securesms.conversation.mutiselect

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.forEach
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.SimpleColorFilter
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationAdapter
import org.thoughtcrime.securesms.util.SetUtil
import org.thoughtcrime.securesms.util.ThemeUtil
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper
import java.lang.Integer.max

/**
 * Decoration which renders the background shade and selection bubble for a {@link Multiselectable} item.
 */
class MultiselectItemDecoration(
  context: Context,
  private val chatWallpaperProvider: () -> ChatWallpaper?
) : RecyclerView.ItemDecoration(), DefaultLifecycleObserver {

  private val path = Path()
  private val rect = Rect()
  private val gutter = ViewUtil.dpToPx(48)
  private val paddingStart = ViewUtil.dpToPx(17)
  private val circleRadius = ViewUtil.dpToPx(11)
  private val checkDrawable = requireNotNull(AppCompatResources.getDrawable(context, R.drawable.ic_check_circle_solid_24)).apply {
    setBounds(0, 0, circleRadius * 2, circleRadius * 2)
  }
  private val photoCircleRadius = ViewUtil.dpToPx(12)
  private val photoCirclePaddingStart = ViewUtil.dpToPx(16)

  private val transparentBlack20 = ContextCompat.getColor(context, R.color.transparent_black_20)
  private val transparentWhite20 = ContextCompat.getColor(context, R.color.transparent_white_20)
  private val transparentWhite60 = ContextCompat.getColor(context, R.color.transparent_white_60)
  private val ultramarine30 = ContextCompat.getColor(context, R.color.core_ultramarine_33)
  private val ultramarine = ContextCompat.getColor(context, R.color.signal_accent_primary)

  private val selectedParts: MutableSet<MultiselectPart> = mutableSetOf()
  private var enterExitAnimation: ValueAnimator? = null
  private val multiselectPartAnimatorMap: MutableMap<MultiselectPart, ValueAnimator> = mutableMapOf()

  private var checkedBitmap: Bitmap? = null

  private var focusedItem: MultiselectPart? = null

  fun setFocusedItem(multiselectPart: MultiselectPart?) {
    this.focusedItem = multiselectPart
  }

  override fun onCreate(owner: LifecycleOwner) {
    val bitmap = Bitmap.createBitmap(circleRadius * 2, circleRadius * 2, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    checkDrawable.draw(canvas)
    checkedBitmap = bitmap
  }

  override fun onDestroy(owner: LifecycleOwner) {
    checkedBitmap?.recycle()
    checkedBitmap = null
  }

  private val shadeColor = ContextCompat.getColor(context, R.color.reactions_screen_shade_color)

  private val unselectedPaint = Paint().apply {
    isAntiAlias = true
    strokeWidth = 1.5f
    style = Paint.Style.STROKE
  }

  private val shadePaint = Paint().apply {
    isAntiAlias = true
    style = Paint.Style.FILL
  }

  private val photoCirclePaint = Paint().apply {
    isAntiAlias = true
    style = Paint.Style.FILL
    color = transparentBlack20
  }

  private val checkPaint = Paint().apply {
    isAntiAlias = true
    style = Paint.Style.FILL
  }

  private fun getCurrentSelection(parent: RecyclerView): Set<MultiselectPart> {
    return (parent.adapter as ConversationAdapter).selectedItems
  }

  override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
    val currentSelection = getCurrentSelection(parent)
    if (selectedParts.isEmpty() && currentSelection.isNotEmpty()) {
      enterExitAnimation?.end()
      enterExitAnimation = ValueAnimator.ofFloat(enterExitAnimation?.animatedFraction ?: 0f, 1f).apply {
        duration = 150L
        start()
      }
    } else if (selectedParts.isNotEmpty() && currentSelection.isEmpty()) {
      enterExitAnimation?.end()
      enterExitAnimation = ValueAnimator.ofFloat(enterExitAnimation?.animatedFraction ?: 1f, 0f).apply {
        duration = 150L
        start()
      }
    }

    if (view is Multiselectable) {
      val parts = view.conversationMessage.multiselectCollection.toSet()
      parts.forEach { updateMultiselectPartAnimator(currentSelection, it) }
    }

    selectedParts.clear()
    selectedParts.addAll(currentSelection)

    outRect.setEmpty()
    updateChildOffsets(parent, view)
  }

  /**
   * Draws the background shade.
   */
  @Suppress("DEPRECATION")
  override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
    val adapter = parent.adapter as ConversationAdapter

    if (adapter.selectedItems.isEmpty()) {
      drawFocusShadeUnderIfNecessary(canvas, parent)
      return
    }

    shadePaint.color = when {
      chatWallpaperProvider() != null -> transparentBlack20
      ThemeUtil.isDarkTheme(parent.context) -> transparentWhite20
      else -> ultramarine30
    }

    parent.children.filterIsInstance(Multiselectable::class.java).forEach { child ->
      updateChildOffsets(parent, child as View)

      val parts: MultiselectCollection = child.conversationMessage.multiselectCollection

      val projections = child.getColorizerProjections(parent)
      if (child.canPlayContent()) {
        projections.add(child.getGiphyMp4PlayableProjection(parent))
      }

      path.reset()
      projections.use { list ->
        list.forEach {
          it.applyToPath(path)
        }
      }

      canvas.save()
      canvas.clipPath(path, Region.Op.DIFFERENCE)

      val selectedParts: Set<MultiselectPart> = SetUtil.intersection(parts.toSet(), adapter.selectedItems)

      if (selectedParts.isNotEmpty()) {
        val selectedPart: MultiselectPart = selectedParts.first()
        val shadeAll = selectedParts.size == parts.size || (selectedPart is MultiselectPart.Text && child.hasNonSelectableMedia())

        if (shadeAll) {
          rect.set(0, child.top, child.right, child.bottom)
        } else {
          rect.set(0, child.getTopBoundaryOfMultiselectPart(selectedPart), parent.right, child.getBottomBoundaryOfMultiselectPart(selectedPart))
        }

        canvas.drawRect(rect, shadePaint)
      }

      canvas.restore()
    }

    drawChecks(parent, canvas, adapter)
  }

  /**
   * Draws the selected check or empty circle.
   */
  override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
    val adapter = parent.adapter as ConversationAdapter
    if (adapter.selectedItems.isEmpty()) {
      drawFocusShadeOverIfNecessary(canvas, parent)
    }

    invalidateIfAnimatorsAreRunning(parent)
  }

  private fun drawChecks(parent: RecyclerView, canvas: Canvas, adapter: ConversationAdapter) {
    val drawCircleBehindSelector = chatWallpaperProvider()?.isPhoto == true
    val multiselectChildren: Sequence<Multiselectable> = parent.children.filterIsInstance(Multiselectable::class.java)

    val isDarkTheme = ThemeUtil.isDarkTheme(parent.context)

    unselectedPaint.color = when {
      chatWallpaperProvider()?.isPhoto == true -> Color.WHITE
      chatWallpaperProvider() != null || isDarkTheme -> transparentWhite60
      else -> transparentBlack20
    }

    if (chatWallpaperProvider() == null && !isDarkTheme) {
      checkPaint.colorFilter = SimpleColorFilter(ultramarine)
    } else {
      checkPaint.colorFilter = null
    }

    multiselectChildren.forEach { child ->
      val parts: MultiselectCollection = child.conversationMessage.multiselectCollection

      parts.toSet().forEach {
        val topBoundary = child.getTopBoundaryOfMultiselectPart(it)
        val bottomBoundary = child.getBottomBoundaryOfMultiselectPart(it)
        if (drawCircleBehindSelector) {
          drawPhotoCircle(canvas, parent, topBoundary, bottomBoundary)
        }

        val alphaProgress = selectedAnimationProgress(it)
        if (adapter.selectedItems.contains(it)) {
          drawUnselectedCircle(canvas, parent, topBoundary, bottomBoundary, 1f - alphaProgress)
          drawSelectedCircle(canvas, parent, topBoundary, bottomBoundary, alphaProgress)
        } else {
          drawUnselectedCircle(canvas, parent, topBoundary, bottomBoundary, alphaProgress)
          if (!isInitialAnimation()) {
            drawSelectedCircle(canvas, parent, topBoundary, bottomBoundary, 1f - alphaProgress)
          }
        }
      }
    }
  }

  /**
   * Draws an extra circle behind the selection circle. This is to make it easier to see and
   * is specifically for when a photo wallpaper is being used.
   */
  private fun drawPhotoCircle(canvas: Canvas, parent: RecyclerView, topBoundary: Int, bottomBoundary: Int) {
    val centerX: Float = if (ViewUtil.isLtr(parent)) {
      photoCirclePaddingStart + photoCircleRadius
    } else {
      parent.right - photoCircleRadius - photoCirclePaddingStart
    }.toFloat()

    val centerY: Float = topBoundary + (bottomBoundary - topBoundary).toFloat() / 2

    canvas.drawCircle(centerX, centerY, photoCircleRadius.toFloat(), photoCirclePaint)
  }

  /**
   * Draws the checkmark for selected content
   */
  private fun drawSelectedCircle(canvas: Canvas, parent: RecyclerView, topBoundary: Int, bottomBoundary: Int, alphaProgress: Float) {
    val topX: Float = if (ViewUtil.isLtr(parent)) {
      paddingStart
    } else {
      parent.right - paddingStart - circleRadius * 2
    }.toFloat()

    val topY: Float = topBoundary + (bottomBoundary - topBoundary).toFloat() / 2 - circleRadius
    val bitmap = checkedBitmap

    val alpha = checkPaint.alpha
    checkPaint.alpha = (alpha * alphaProgress).toInt()

    if (bitmap != null) {
      canvas.drawBitmap(bitmap, topX, topY, checkPaint)
    }

    checkPaint.alpha = alpha
  }

  /**
   * Draws the empty circle for unselected content
   */
  private fun drawUnselectedCircle(c: Canvas, parent: RecyclerView, topBoundary: Int, bottomBoundary: Int, alphaProgress: Float) {
    val centerX: Float = if (ViewUtil.isLtr(parent)) {
      paddingStart + circleRadius
    } else {
      parent.right - circleRadius - paddingStart
    }.toFloat()

    val alpha = unselectedPaint.alpha
    unselectedPaint.alpha = (alpha * alphaProgress).toInt()
    val centerY: Float = topBoundary + (bottomBoundary - topBoundary).toFloat() / 2

    c.drawCircle(centerX, centerY, circleRadius.toFloat(), unselectedPaint)
    unselectedPaint.alpha = alpha
  }

  /**
   * Update the start-aligned gutter in which the checks display. This is called in onDraw to
   * ensure we don't hit situations where we try to set offsets before items are laid out, and
   * called in getItemOffsets to ensure the gutter goes away when multiselect mode ends.
   */
  private fun updateChildOffsets(parent: RecyclerView, child: View) {
    val adapter = parent.adapter as ConversationAdapter
    val isLtr = ViewUtil.isLtr(child)

    if (adapter.selectedItems.isNotEmpty() && child is Multiselectable) {
      val target = child.getHorizontalTranslationTarget()

      if (target != null) {
        val start = if (isLtr) {
          target.left
        } else {
          parent.right - target.right
        }

        val translation: Float = if (isInitialAnimation()) {
          max(0, gutter - start) * (enterExitAnimation?.animatedFraction ?: 1f)
        } else {
          max(0, gutter - start).toFloat()
        }

        child.translationX = if (isLtr) {
          translation
        } else {
          -translation
        }
      }
    } else if (child is Multiselectable) {
      child.translationX = 0f
    }
  }

  private fun drawFocusShadeUnderIfNecessary(canvas: Canvas, parent: RecyclerView) {
    val inFocus = focusedItem
    if (inFocus != null) {
      path.reset()
      canvas.save()

      parent.forEach { child ->
        if (child is Multiselectable && child.conversationMessage == inFocus.conversationMessage) {
          path.addRect(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat(), Path.Direction.CW)
          child.getColorizerProjections(parent).use { list ->
            list.forEach {
              path.op(it.path, Path.Op.DIFFERENCE)
            }
          }

          if (child.canPlayContent()) {
            val mp4GifProjection = child.getGiphyMp4PlayableProjection(child.rootView as ViewGroup)
            path.op(mp4GifProjection.path, Path.Op.DIFFERENCE)
            mp4GifProjection.release()
          }
        }
      }

      canvas.clipPath(path)
      canvas.drawColor(shadeColor)
      canvas.restore()
    }
  }

  private fun drawFocusShadeOverIfNecessary(canvas: Canvas, parent: RecyclerView) {
    val inFocus = focusedItem
    if (inFocus != null) {
      path.reset()
      canvas.save()

      parent.forEach { child ->
        if (child is Multiselectable && child.conversationMessage == inFocus.conversationMessage) {
          path.addRect(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat(), Path.Direction.CW)
        }
      }

      canvas.clipPath(path, Region.Op.DIFFERENCE)
      canvas.drawColor(shadeColor)
      canvas.restore()
    }
  }

  private fun isInitialAnimation(): Boolean {
    return (enterExitAnimation?.animatedFraction ?: 0f) < 1f
  }

  // This is reentrant
  private fun updateMultiselectPartAnimator(currentSelection: Set<MultiselectPart>, multiselectPart: MultiselectPart) {
    val difference: Difference = getDifferenceForPart(currentSelection, multiselectPart)
    val animator: ValueAnimator? = multiselectPartAnimatorMap[multiselectPart]

    when (difference) {
      Difference.SAME -> Unit
      Difference.ADDED -> {
        val newAnimator = ValueAnimator.ofFloat(animator?.animatedFraction ?: 0f, 1f).apply {
          duration = 150L
          start()
        }
        animator?.end()
        multiselectPartAnimatorMap[multiselectPart] = newAnimator
      }
      Difference.REMOVED -> {
        val newAnimator = ValueAnimator.ofFloat(animator?.animatedFraction ?: 1f, 0f).apply {
          duration = 150L
          start()
        }
        animator?.end()
        multiselectPartAnimatorMap[multiselectPart] = newAnimator
      }
    }
  }

  private fun selectedAnimationProgress(multiselectPart: MultiselectPart): Float {
    val animator = multiselectPartAnimatorMap[multiselectPart]
    return animator?.animatedFraction ?: 1f
  }

  private fun getDifferenceForPart(currentSelection: Set<MultiselectPart>, multiselectPart: MultiselectPart): Difference {
    val isSelected = currentSelection.contains(multiselectPart)
    val wasSelected = selectedParts.contains(multiselectPart)

    return when {
      isSelected && !wasSelected -> Difference.ADDED
      !isSelected && wasSelected -> Difference.REMOVED
      else -> Difference.SAME
    }
  }

  private fun invalidateIfAnimatorsAreRunning(parent: RecyclerView) {
    if (enterExitAnimation?.isRunning == true || multiselectPartAnimatorMap.values.any { it.isRunning }) {
      parent.invalidate()
    }
  }

  private enum class Difference {
    REMOVED,
    ADDED,
    SAME
  }
}
