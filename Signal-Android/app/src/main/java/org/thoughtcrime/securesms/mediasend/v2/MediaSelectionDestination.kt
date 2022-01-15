package org.thoughtcrime.securesms.mediasend.v2

import android.os.Bundle
import org.thoughtcrime.securesms.recipients.RecipientId

sealed class MediaSelectionDestination {

  object Wallpaper : MediaSelectionDestination() {
    override fun toBundle(): Bundle {
      return Bundle().apply {
        putBoolean(WALLPAPER, true)
      }
    }
  }

  object Avatar : MediaSelectionDestination() {
    override fun toBundle(): Bundle {
      return Bundle().apply {
        putBoolean(AVATAR, true)
      }
    }
  }

  object ChooseAfterMediaSelection : MediaSelectionDestination() {
    override fun toBundle(): Bundle {
      return Bundle.EMPTY
    }
  }

  class SingleRecipient(private val id: RecipientId) : MediaSelectionDestination() {
    override fun getRecipientId(): RecipientId = id

    override fun toBundle(): Bundle {
      return Bundle().apply {
        putParcelable(RECIPIENT, id)
      }
    }
  }

  class MultipleRecipients(val recipientIds: List<RecipientId>) : MediaSelectionDestination() {
    override fun getRecipientIdList(): List<RecipientId> = recipientIds

    override fun toBundle(): Bundle {
      return Bundle().apply {
        putParcelableArrayList(RECIPIENT_LIST, ArrayList(recipientIds))
      }
    }
  }

  open fun getRecipientId(): RecipientId? = null
  open fun getRecipientIdList(): List<RecipientId> = emptyList()

  abstract fun toBundle(): Bundle

  companion object {
    private const val WALLPAPER = "wallpaper"
    private const val AVATAR = "avatar"
    private const val RECIPIENT = "recipient"
    private const val RECIPIENT_LIST = "recipient_list"

    fun fromBundle(bundle: Bundle): MediaSelectionDestination {
      return when {
        bundle.containsKey(WALLPAPER) -> Wallpaper
        bundle.containsKey(AVATAR) -> Avatar
        bundle.containsKey(RECIPIENT) -> SingleRecipient(requireNotNull(bundle.getParcelable(RECIPIENT)))
        bundle.containsKey(RECIPIENT_LIST) -> MultipleRecipients(requireNotNull(bundle.getParcelableArrayList(RECIPIENT_LIST)))
        else -> ChooseAfterMediaSelection
      }
    }
  }
}
