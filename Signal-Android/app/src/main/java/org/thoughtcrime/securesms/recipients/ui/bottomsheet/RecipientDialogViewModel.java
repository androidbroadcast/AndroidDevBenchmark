package org.thoughtcrime.securesms.recipients.ui.bottomsheet;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.signal.core.util.ThreadUtil;
import org.thoughtcrime.securesms.BlockUnblockDialog;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.verify.VerifyIdentityActivity;
import org.thoughtcrime.securesms.components.settings.conversation.ConversationSettingsActivity;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.model.IdentityRecord;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.LiveGroup;
import org.thoughtcrime.securesms.groups.ui.GroupChangeFailureReason;
import org.thoughtcrime.securesms.groups.ui.GroupErrors;
import org.thoughtcrime.securesms.groups.ui.addtogroup.AddToGroupsActivity;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.util.CommunicationActions;
import org.thoughtcrime.securesms.util.livedata.LiveDataUtil;

import java.util.Objects;

final class RecipientDialogViewModel extends ViewModel {

  private final Context                         context;
  private final RecipientDialogRepository       recipientDialogRepository;
  private final LiveData<Recipient>             recipient;
  private final MutableLiveData<IdentityRecord> identity;
  private final LiveData<AdminActionStatus>     adminActionStatus;
  private final LiveData<Boolean>               canAddToAGroup;
  private final MutableLiveData<Boolean>        adminActionBusy;

  private RecipientDialogViewModel(@NonNull Context context,
                                   @NonNull RecipientDialogRepository recipientDialogRepository)
  {
    this.context                   = context;
    this.recipientDialogRepository = recipientDialogRepository;
    this.identity                  = new MutableLiveData<>();
    this.adminActionBusy           = new MutableLiveData<>(false);

    boolean recipientIsSelf = recipientDialogRepository.getRecipientId().equals(Recipient.self().getId());

    recipient = Recipient.live(recipientDialogRepository.getRecipientId()).getLiveData();

    if (recipientDialogRepository.getGroupId() != null && recipientDialogRepository.getGroupId().isV2() && !recipientIsSelf) {
      LiveGroup source = new LiveGroup(recipientDialogRepository.getGroupId());

      LiveData<Boolean>                   localIsAdmin         = source.isSelfAdmin();
      LiveData<GroupDatabase.MemberLevel> recipientMemberLevel = Transformations.switchMap(recipient, source::getMemberLevel);

      adminActionStatus = LiveDataUtil.combineLatest(localIsAdmin, recipientMemberLevel,
        (localAdmin, memberLevel) -> {
          boolean inGroup        = memberLevel.isInGroup();
          boolean recipientAdmin = memberLevel == GroupDatabase.MemberLevel.ADMINISTRATOR;

          return new AdminActionStatus(inGroup && localAdmin,
                                       inGroup && localAdmin && !recipientAdmin,
                                       inGroup && localAdmin && recipientAdmin);
        });
    } else {
      adminActionStatus = new MutableLiveData<>(new AdminActionStatus(false, false, false));
    }

    boolean isSelf = recipientDialogRepository.getRecipientId().equals(Recipient.self().getId());
    if (!isSelf) {
      recipientDialogRepository.getIdentity(identity::postValue);
    }

    MutableLiveData<Integer> localGroupCount = new MutableLiveData<>(0);

    canAddToAGroup = LiveDataUtil.combineLatest(recipient, localGroupCount,
                                                (r, count) -> count > 0 && r.isRegistered() && !r.isGroup() && !r.isSelf());

    recipientDialogRepository.getActiveGroupCount(localGroupCount::postValue);
  }

  LiveData<Recipient> getRecipient() {
    return recipient;
  }

  public LiveData<Boolean> getCanAddToAGroup() {
    return canAddToAGroup;
  }

  LiveData<AdminActionStatus> getAdminActionStatus() {
    return adminActionStatus;
  }

  LiveData<IdentityRecord> getIdentity() {
    return identity;
  }

  LiveData<Boolean> getAdminActionBusy() {
    return adminActionBusy;
  }

  void onMessageClicked(@NonNull Activity activity) {
    recipientDialogRepository.getRecipient(recipient -> CommunicationActions.startConversation(activity, recipient, null));
  }

  void onSecureCallClicked(@NonNull FragmentActivity activity) {
    recipientDialogRepository.getRecipient(recipient -> CommunicationActions.startVoiceCall(activity, recipient));
  }

  void onInsecureCallClicked(@NonNull FragmentActivity activity) {
    recipientDialogRepository.getRecipient(recipient -> CommunicationActions.startInsecureCall(activity, recipient));
  }

  void onSecureVideoCallClicked(@NonNull FragmentActivity activity) {
    recipientDialogRepository.getRecipient(recipient -> CommunicationActions.startVideoCall(activity, recipient));
  }

  void onBlockClicked(@NonNull FragmentActivity activity) {
    recipientDialogRepository.getRecipient(recipient -> BlockUnblockDialog.showBlockFor(activity, activity.getLifecycle(), recipient, () -> RecipientUtil.blockNonGroup(context, recipient)));
  }

  void onUnblockClicked(@NonNull FragmentActivity activity) {
    recipientDialogRepository.getRecipient(recipient -> BlockUnblockDialog.showUnblockFor(activity, activity.getLifecycle(), recipient, () -> RecipientUtil.unblock(context, recipient)));
  }

  void onViewSafetyNumberClicked(@NonNull Activity activity, @NonNull IdentityRecord identityRecord) {
    activity.startActivity(VerifyIdentityActivity.newIntent(activity, identityRecord));
  }

  void onAvatarClicked(@NonNull Activity activity) {
    activity.startActivity(ConversationSettingsActivity.forRecipient(activity, recipientDialogRepository.getRecipientId()));
  }

  void onMakeGroupAdminClicked(@NonNull Activity activity) {
    new AlertDialog.Builder(activity)
                   .setMessage(context.getString(R.string.RecipientBottomSheet_s_will_be_able_to_edit_group, Objects.requireNonNull(recipient.getValue()).getDisplayName(context)))
                   .setPositiveButton(R.string.RecipientBottomSheet_make_admin,
                                      (dialog, which) -> {
                                        adminActionBusy.setValue(true);
                                        recipientDialogRepository.setMemberAdmin(true, result -> {
                                          adminActionBusy.setValue(false);
                                          if (!result) {
                                            Toast.makeText(activity, R.string.ManageGroupActivity_failed_to_update_the_group, Toast.LENGTH_SHORT).show();
                                          }
                                        },
                                        this::showErrorToast);
                                      })
                   .setNegativeButton(android.R.string.cancel, (dialog, which) -> {})
                   .show();
  }

  void onRemoveGroupAdminClicked(@NonNull Activity activity) {
    new AlertDialog.Builder(activity)
                   .setMessage(context.getString(R.string.RecipientBottomSheet_remove_s_as_group_admin, Objects.requireNonNull(recipient.getValue()).getDisplayName(context)))
                   .setPositiveButton(R.string.RecipientBottomSheet_remove_as_admin,
                                      (dialog, which) -> {
                                        adminActionBusy.setValue(true);
                                        recipientDialogRepository.setMemberAdmin(false, result -> {
                                          adminActionBusy.setValue(false);
                                          if (!result) {
                                            Toast.makeText(activity, R.string.ManageGroupActivity_failed_to_update_the_group, Toast.LENGTH_SHORT).show();
                                          }
                                        },
                                        this::showErrorToast);
                                      })
                   .setNegativeButton(android.R.string.cancel, (dialog, which) -> {})
                   .show();
  }

  void onRemoveFromGroupClicked(@NonNull Activity activity, @NonNull Runnable onSuccess) {
    new AlertDialog.Builder(activity)
                   .setMessage(context.getString(R.string.RecipientBottomSheet_remove_s_from_the_group, Objects.requireNonNull(recipient.getValue()).getDisplayName(context)))
                   .setPositiveButton(R.string.RecipientBottomSheet_remove,
                                      (dialog, which) -> {
                                        adminActionBusy.setValue(true);
                                        recipientDialogRepository.removeMember(result -> {
                                          adminActionBusy.setValue(false);
                                          if (result) {
                                            onSuccess.run();
                                          }
                                        },
                                        this::showErrorToast);
                                      })
                   .setNegativeButton(android.R.string.cancel, (dialog, which) -> {})
                   .show();
  }

  void refreshRecipient() {
    recipientDialogRepository.refreshRecipient();
  }

  void onAddToGroupButton(@NonNull Activity activity) {
    recipientDialogRepository.getGroupMembership(existingGroups -> activity.startActivity(AddToGroupsActivity.newIntent(activity, recipientDialogRepository.getRecipientId(), existingGroups)));
  }

  @WorkerThread
  private void showErrorToast(@NonNull GroupChangeFailureReason e) {
    ThreadUtil.runOnMain(() -> Toast.makeText(context, GroupErrors.getUserDisplayMessage(e), Toast.LENGTH_LONG).show());
  }

  static class AdminActionStatus {
    private final boolean canRemove;
    private final boolean canMakeAdmin;
    private final boolean canMakeNonAdmin;

    AdminActionStatus(boolean canRemove, boolean canMakeAdmin, boolean canMakeNonAdmin) {
      this.canRemove       = canRemove;
      this.canMakeAdmin    = canMakeAdmin;
      this.canMakeNonAdmin = canMakeNonAdmin;
    }

    boolean isCanRemove() {
      return canRemove;
    }

    boolean isCanMakeAdmin() {
      return canMakeAdmin;
    }

    boolean isCanMakeNonAdmin() {
      return canMakeNonAdmin;
    }
  }

  public static class Factory implements ViewModelProvider.Factory {

    private final Context     context;
    private final RecipientId recipientId;
    private final GroupId     groupId;

    Factory(@NonNull Context context, @NonNull RecipientId recipientId, @Nullable GroupId groupId) {
      this.context     = context;
      this.recipientId = recipientId;
      this.groupId     = groupId;
    }

    @Override
    public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      //noinspection unchecked
      return (T) new RecipientDialogViewModel(context, new RecipientDialogRepository(context, recipientId, groupId));
    }
  }
}
