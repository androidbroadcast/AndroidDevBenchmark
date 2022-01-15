package org.thoughtcrime.securesms.components.settings.conversation

import android.database.Cursor
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.signal.core.util.ThreadUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.components.settings.conversation.preferences.ButtonStripPreference
import org.thoughtcrime.securesms.components.settings.conversation.preferences.LegacyGroupPreference
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.LiveGroup
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.util.CursorUtil
import org.thoughtcrime.securesms.util.FeatureFlags
import org.thoughtcrime.securesms.util.SingleLiveEvent
import org.thoughtcrime.securesms.util.livedata.LiveDataUtil
import org.thoughtcrime.securesms.util.livedata.Store
import org.whispersystems.libsignal.util.guava.Optional

sealed class ConversationSettingsViewModel(
  private val repository: ConversationSettingsRepository,
  specificSettingsState: SpecificSettingsState,
) : ViewModel() {

  private val openedMediaCursors = HashSet<Cursor>()

  @Volatile
  private var cleared = false

  protected val store = Store(
    ConversationSettingsState(
      specificSettingsState = specificSettingsState
    )
  )
  protected val internalEvents = SingleLiveEvent<ConversationSettingsEvent>()

  private val sharedMediaUpdateTrigger = MutableLiveData(Unit)

  val state: LiveData<ConversationSettingsState> = store.stateLiveData
  val events: LiveData<ConversationSettingsEvent> = internalEvents

  init {
    val threadId: LiveData<Long> = Transformations.distinctUntilChanged(Transformations.map(state) { it.threadId })
    val updater: LiveData<Long> = LiveDataUtil.combineLatest(threadId, sharedMediaUpdateTrigger) { tId, _ -> tId }

    val sharedMedia: LiveData<Optional<Cursor>> = LiveDataUtil.mapAsync(SignalExecutors.BOUNDED, updater) { tId ->
      repository.getThreadMedia(tId)
    }

    store.update(sharedMedia) { cursor, state ->
      if (!cleared) {
        if (cursor.isPresent) {
          openedMediaCursors.add(cursor.get())
        }

        val ids: List<Long> = cursor.transform<List<Long>> {
          val result = mutableListOf<Long>()
          while (it.moveToNext()) {
            result.add(CursorUtil.requireLong(it, AttachmentDatabase.ROW_ID))
          }
          result
        }.or(listOf())

        state.copy(
          sharedMedia = cursor.orNull(),
          sharedMediaIds = ids,
          sharedMediaLoaded = true,
          displayInternalRecipientDetails = repository.isInternalRecipientDetailsEnabled()
        )
      } else {
        cursor.orNull().ensureClosed()
        state.copy(sharedMedia = null)
      }
    }
  }

  fun refreshSharedMedia() {
    sharedMediaUpdateTrigger.postValue(Unit)
  }

  open fun refreshRecipient(): Unit = error("This ViewModel does not support this interaction")

  abstract fun setMuteUntil(muteUntil: Long)

  abstract fun unmute()

  abstract fun block()

  abstract fun unblock()

  abstract fun onAddToGroup()

  abstract fun onAddToGroupComplete(selected: List<RecipientId>, onComplete: () -> Unit)

  abstract fun revealAllMembers()

  override fun onCleared() {
    cleared = true
    openedMediaCursors.forEach { it.ensureClosed() }
    store.clear()
  }

  private fun Cursor?.ensureClosed() {
    if (this != null && !this.isClosed) {
      this.close()
    }
  }

  open fun initiateGroupUpgrade(): Unit = error("This ViewModel does not support this interaction")

  private class RecipientSettingsViewModel(
    private val recipientId: RecipientId,
    private val repository: ConversationSettingsRepository
  ) : ConversationSettingsViewModel(
    repository,
    SpecificSettingsState.RecipientSettingsState()
  ) {

    private val liveRecipient = Recipient.live(recipientId)

    init {
      store.update(liveRecipient.liveData) { recipient, state ->
        state.copy(
          recipient = recipient,
          buttonStripState = ButtonStripPreference.State(
            isVideoAvailable = recipient.registered == RecipientDatabase.RegisteredState.REGISTERED && !recipient.isSelf,
            isAudioAvailable = !recipient.isGroup && !recipient.isSelf,
            isAudioSecure = recipient.registered == RecipientDatabase.RegisteredState.REGISTERED,
            isMuted = recipient.isMuted,
            isMuteAvailable = !recipient.isSelf,
            isSearchAvailable = true
          ),
          disappearingMessagesLifespan = recipient.expiresInSeconds,
          canModifyBlockedState = !recipient.isSelf && RecipientUtil.isBlockable(recipient),
          specificSettingsState = state.requireRecipientSettingsState().copy(
            contactLinkState = when {
              recipient.isSelf -> ContactLinkState.NONE
              recipient.isSystemContact -> ContactLinkState.OPEN
              else -> ContactLinkState.ADD
            }
          )
        )
      }

      repository.getThreadId(recipientId) { threadId ->
        store.update { state ->
          state.copy(threadId = threadId)
        }
      }

      if (recipientId != Recipient.self().id) {
        repository.getGroupsInCommon(recipientId) { groupsInCommon ->
          store.update { state ->
            val recipientSettings = state.requireRecipientSettingsState()
            val canShowMore = !recipientSettings.groupsInCommonExpanded && groupsInCommon.size > 6

            state.copy(
              specificSettingsState = recipientSettings.copy(
                allGroupsInCommon = groupsInCommon,
                groupsInCommon = if (!canShowMore) groupsInCommon else groupsInCommon.take(5),
                canShowMoreGroupsInCommon = canShowMore
              )
            )
          }
        }

        repository.hasGroups { hasGroups ->
          store.update { state ->
            val recipientSettings = state.requireRecipientSettingsState()
            state.copy(
              specificSettingsState = recipientSettings.copy(
                selfHasGroups = hasGroups
              )
            )
          }
        }

        repository.getIdentity(recipientId) { identityRecord ->
          store.update { state ->
            state.copy(specificSettingsState = state.requireRecipientSettingsState().copy(identityRecord = identityRecord))
          }
        }
      }
    }

    override fun onAddToGroup() {
      repository.getGroupMembership(recipientId) {
        internalEvents.postValue(ConversationSettingsEvent.AddToAGroup(recipientId, it))
      }
    }

    override fun onAddToGroupComplete(selected: List<RecipientId>, onComplete: () -> Unit) {
    }

    override fun revealAllMembers() {
      store.update { state ->
        state.copy(
          specificSettingsState = state.requireRecipientSettingsState().copy(
            groupsInCommon = state.requireRecipientSettingsState().allGroupsInCommon,
            groupsInCommonExpanded = true,
            canShowMoreGroupsInCommon = false
          )
        )
      }
    }

    override fun refreshRecipient() {
      repository.refreshRecipient(recipientId)
    }

    override fun setMuteUntil(muteUntil: Long) {
      repository.setMuteUntil(recipientId, muteUntil)
    }

    override fun unmute() {
      repository.setMuteUntil(recipientId, 0)
    }

    override fun block() {
      repository.block(recipientId)
    }

    override fun unblock() {
      repository.unblock(recipientId)
    }
  }

  private class GroupSettingsViewModel(
    private val groupId: GroupId,
    private val repository: ConversationSettingsRepository
  ) : ConversationSettingsViewModel(repository, SpecificSettingsState.GroupSettingsState(groupId)) {

    private val liveGroup = LiveGroup(groupId)

    init {
      store.update(liveGroup.groupRecipient) { recipient, state ->
        state.copy(
          recipient = recipient,
          buttonStripState = ButtonStripPreference.State(
            isVideoAvailable = recipient.isPushV2Group,
            isAudioAvailable = false,
            isAudioSecure = recipient.isPushV2Group,
            isMuted = recipient.isMuted,
            isMuteAvailable = true,
            isSearchAvailable = true
          ),
          canModifyBlockedState = RecipientUtil.isBlockable(recipient),
          specificSettingsState = state.requireGroupSettingsState().copy(
            legacyGroupState = getLegacyGroupState(recipient)
          )
        )
      }

      repository.getThreadId(groupId) { threadId ->
        store.update { state ->
          state.copy(threadId = threadId)
        }
      }

      store.update(liveGroup.selfCanEditGroupAttributes()) { selfCanEditGroupAttributes, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            canEditGroupAttributes = selfCanEditGroupAttributes
          )
        )
      }

      store.update(liveGroup.isSelfAdmin) { isSelfAdmin, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            isSelfAdmin = isSelfAdmin
          )
        )
      }

      store.update(liveGroup.expireMessages) { expireMessages, state ->
        state.copy(
          disappearingMessagesLifespan = expireMessages
        )
      }

      store.update(liveGroup.selfCanAddMembers()) { canAddMembers, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            canAddToGroup = canAddMembers
          )
        )
      }

      store.update(liveGroup.fullMembers) { fullMembers, state ->
        val groupState = state.requireGroupSettingsState()
        val canShowMore = !groupState.groupMembersExpanded && fullMembers.size > 6

        state.copy(
          specificSettingsState = groupState.copy(
            allMembers = fullMembers,
            members = if (!canShowMore) fullMembers else fullMembers.take(5),
            canShowMoreGroupMembers = canShowMore
          )
        )
      }

      store.update(liveGroup.isAnnouncementGroup) { announcementGroup, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            isAnnouncementGroup = announcementGroup
          )
        )
      }

      val isMessageRequestAccepted: LiveData<Boolean> = LiveDataUtil.mapAsync(liveGroup.groupRecipient) { r -> repository.isMessageRequestAccepted(r) }
      val descriptionState: LiveData<DescriptionState> = LiveDataUtil.combineLatest(liveGroup.description, isMessageRequestAccepted, ::DescriptionState)

      store.update(descriptionState) { d, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            groupDescription = d.description,
            groupDescriptionShouldLinkify = d.canLinkify,
            groupDescriptionLoaded = true
          )
        )
      }

      store.update(liveGroup.isActive) { isActive, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            canLeave = isActive && groupId.isPush
          )
        )
      }

      store.update(liveGroup.title) { title, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            groupTitle = title,
            groupTitleLoaded = true
          )
        )
      }

      store.update(liveGroup.groupLink) { groupLink, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            groupLinkEnabled = groupLink.isEnabled
          )
        )
      }

      store.update(repository.getMembershipCountDescription(liveGroup)) { description, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            membershipCountDescription = description
          )
        )
      }
    }

    private fun getLegacyGroupState(recipient: Recipient): LegacyGroupPreference.State {
      val showLegacyInfo = recipient.requireGroupId().isV1

      return if (showLegacyInfo && recipient.participants.size > FeatureFlags.groupLimits().hardLimit) {
        LegacyGroupPreference.State.TOO_LARGE
      } else if (showLegacyInfo) {
        LegacyGroupPreference.State.UPGRADE
      } else if (groupId.isMms) {
        LegacyGroupPreference.State.MMS_WARNING
      } else {
        LegacyGroupPreference.State.NONE
      }
    }

    override fun onAddToGroup() {
      repository.getGroupCapacity(groupId) { capacityResult ->
        if (capacityResult.getRemainingCapacity() > 0) {

          internalEvents.postValue(
            ConversationSettingsEvent.AddMembersToGroup(
              groupId,
              capacityResult.getSelectionWarning(),
              capacityResult.getSelectionLimit(),
              capacityResult.isAnnouncementGroup,
              capacityResult.getMembersWithoutSelf()
            )
          )
        } else {
          internalEvents.postValue(ConversationSettingsEvent.ShowGroupHardLimitDialog)
        }
      }
    }

    override fun onAddToGroupComplete(selected: List<RecipientId>, onComplete: () -> Unit) {
      repository.addMembers(groupId, selected) {
        ThreadUtil.runOnMain { onComplete() }

        when (it) {
          is GroupAddMembersResult.Success -> {
            if (it.newMembersInvited.isNotEmpty()) {
              internalEvents.postValue(ConversationSettingsEvent.ShowGroupInvitesSentDialog(it.newMembersInvited))
            }

            if (it.numberOfMembersAdded > 0) {
              internalEvents.postValue(ConversationSettingsEvent.ShowMembersAdded(it.numberOfMembersAdded))
            }
          }
          is GroupAddMembersResult.Failure -> internalEvents.postValue(ConversationSettingsEvent.ShowAddMembersToGroupError(it.reason))
        }
      }
    }

    override fun revealAllMembers() {
      store.update { state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            members = state.requireGroupSettingsState().allMembers,
            groupMembersExpanded = true,
            canShowMoreGroupMembers = false
          )
        )
      }
    }

    override fun setMuteUntil(muteUntil: Long) {
      repository.setMuteUntil(groupId, muteUntil)
    }

    override fun unmute() {
      repository.setMuteUntil(groupId, 0)
    }

    override fun block() {
      repository.block(groupId)
    }

    override fun unblock() {
      repository.unblock(groupId)
    }

    override fun initiateGroupUpgrade() {
      repository.getExternalPossiblyMigratedGroupRecipientId(groupId) {
        internalEvents.postValue(ConversationSettingsEvent.InitiateGroupMigration(it))
      }
    }
  }

  class Factory(
    private val recipientId: RecipientId? = null,
    private val groupId: GroupId? = null,
    private val repository: ConversationSettingsRepository,
  ) : ViewModelProvider.Factory {

    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
      return requireNotNull(
        modelClass.cast(
          when {
            recipientId != null -> RecipientSettingsViewModel(recipientId, repository)
            groupId != null -> GroupSettingsViewModel(groupId, repository)
            else -> error("One of RecipientId or GroupId required.")
          }
        )
      )
    }
  }

  private class DescriptionState(
    val description: String?,
    val canLinkify: Boolean
  )
}
