package org.thoughtcrime.securesms.recipients;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.signal.zkgroup.profiles.ProfileKeyCredential;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.badges.models.Badge;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.GeneratedContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.GroupRecordContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ProfileContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ResourceContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.SystemContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.TransparentContactPhoto;
import org.thoughtcrime.securesms.conversation.colors.AvatarColor;
import org.thoughtcrime.securesms.conversation.colors.ChatColors;
import org.thoughtcrime.securesms.conversation.colors.ChatColorsPalette;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.MentionSetting;
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState;
import org.thoughtcrime.securesms.database.RecipientDatabase.UnidentifiedAccessMode;
import org.thoughtcrime.securesms.database.RecipientDatabase.VibrateState;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.databaseprotos.RecipientExtras;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.phonenumbers.NumberUtil;
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter;
import org.thoughtcrime.securesms.profiles.ProfileName;
import org.thoughtcrime.securesms.util.AvatarUtil;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.StringUtil;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.libsignal.util.guava.Preconditions;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.PNI;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.util.UuidUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.thoughtcrime.securesms.database.RecipientDatabase.InsightsBannerTier;

public class Recipient {

  private static final String TAG = Log.tag(Recipient.class);

  public static final Recipient UNKNOWN = new Recipient(RecipientId.UNKNOWN, new RecipientDetails(), true);

  public static final FallbackPhotoProvider DEFAULT_FALLBACK_PHOTO_PROVIDER = new FallbackPhotoProvider();

  private static final int MAX_MEMBER_NAMES = 10;

  private final RecipientId            id;
  private final boolean                resolving;
  private final ACI                    aci;
  private final PNI                    pni;
  private final String                 username;
  private final String                 e164;
  private final String                 email;
  private final GroupId                groupId;
  private final List<Recipient>        participants;
  private final Optional<Long>         groupAvatarId;
  private final boolean                isSelf;
  private final boolean                blocked;
  private final long                   muteUntil;
  private final VibrateState           messageVibrate;
  private final VibrateState           callVibrate;
  private final Uri                    messageRingtone;
  private final Uri                    callRingtone;
  private final Optional<Integer>      defaultSubscriptionId;
  private final int                    expireMessages;
  private final RegisteredState        registered;
  private final byte[]                 profileKey;
  private final ProfileKeyCredential   profileKeyCredential;
  private final String                 groupName;
  private final Uri                    systemContactPhoto;
  private final String                 customLabel;
  private final Uri                    contactUri;
  private final ProfileName            signalProfileName;
  private final String                 profileAvatar;
  private final boolean                hasProfileImage;
  private final boolean                profileSharing;
  private final long                   lastProfileFetch;
  private final String                 notificationChannel;
  private final UnidentifiedAccessMode unidentifiedAccessMode;
  private final boolean                forceSmsSelection;
  private final Capability             groupsV2Capability;
  private final Capability             groupsV1MigrationCapability;
  private final Capability             senderKeyCapability;
  private final Capability             announcementGroupCapability;
  private final Capability             changeNumberCapability;
  private final InsightsBannerTier     insightsBannerTier;
  private final byte[]                 storageId;
  private final MentionSetting         mentionSetting;
  private final ChatWallpaper          wallpaper;
  private final ChatColors             chatColors;
  private final AvatarColor            avatarColor;
  private final String                 about;
  private final String                 aboutEmoji;
  private final ProfileName            systemProfileName;
  private final String                 systemContactName;
  private final Optional<Extras>       extras;
  private final boolean                hasGroupsInCommon;
  private final List<Badge>            badges;

  /**
   * Returns a {@link LiveRecipient}, which contains a {@link Recipient} that may or may not be
   * populated with data. However, you can observe the value that's returned to be notified when the
   * {@link Recipient} changes.
   */
  @AnyThread
  public static @NonNull LiveRecipient live(@NonNull RecipientId id) {
    Preconditions.checkNotNull(id, "ID cannot be null.");
    return ApplicationDependencies.getRecipientCache().getLive(id);
  }

  /**
   * Returns a fully-populated {@link Recipient}. May hit the disk, and therefore should be
   * called on a background thread.
   */
  @WorkerThread
  public static @NonNull Recipient resolved(@NonNull RecipientId id) {
    Preconditions.checkNotNull(id, "ID cannot be null.");
    return live(id).resolve();
  }

  @WorkerThread
  public static @NonNull List<Recipient> resolvedList(@NonNull Collection<RecipientId> ids) {
    List<Recipient> recipients = new ArrayList<>(ids.size());

    for (RecipientId recipientId : ids) {
      recipients.add(resolved(recipientId));
    }

    return recipients;
  }

  /**
   * Returns a fully-populated {@link Recipient} and associates it with the provided username.
   */
  @WorkerThread
  public static @NonNull Recipient externalUsername(@NonNull Context context, @NonNull ACI aci, @NonNull String username) {
    Recipient recipient = externalPush(context, aci, null, false);
    SignalDatabase.recipients().setUsername(recipient.getId(), username);
    return recipient;
  }

  /**
   * Returns a fully-populated {@link Recipient} based off of a {@link SignalServiceAddress},
   * creating one in the database if necessary. Convenience overload of
   * {@link #externalPush(Context, ACI, String, boolean)}
   */
  @WorkerThread
  public static @NonNull Recipient externalPush(@NonNull Context context, @NonNull SignalServiceAddress signalServiceAddress) {
    return externalPush(context, signalServiceAddress.getAci(), signalServiceAddress.getNumber().orNull(), false);
  }

  /**
   * Returns a fully-populated {@link Recipient} based off of a {@link SignalServiceAddress},
   * creating one in the database if necessary. We special-case GV1 members because we want to
   * prioritize E164 addresses and not use the UUIDs if possible.
   */
  @WorkerThread
  public static @NonNull Recipient externalGV1Member(@NonNull Context context, @NonNull SignalServiceAddress address) {
    if (address.getNumber().isPresent()) {
      return externalPush(context, null, address.getNumber().get(), false);
    } else {
      return externalPush(context, address.getAci(), null, false);
    }
  }

  /**
   * Returns a fully-populated {@link Recipient} based off of a {@link SignalServiceAddress},
   * creating one in the database if necessary. This should only used for high-trust sources,
   * which are limited to:
   * - Envelopes
   * - UD Certs
   * - CDS
   * - Storage Service
   */
  @WorkerThread
  public static @NonNull Recipient externalHighTrustPush(@NonNull Context context, @NonNull SignalServiceAddress signalServiceAddress) {
    return externalPush(context, signalServiceAddress.getAci(), signalServiceAddress.getNumber().orNull(), true);
  }

  /**
   * Returns a fully-populated {@link Recipient} based off of a UUID and phone number, creating one
   * in the database if necessary. We want both piece of information so we're able to associate them
   * both together, depending on which are available.
   *
   * In particular, while we'll eventually get the UUID of a user created via a phone number
   * (through a directory sync), the only way we can store the phone number is by retrieving it from
   * sent messages and whatnot. So we should store it when available.
   *
   * @param highTrust This should only be set to true if the source of the E164-UUID pairing is one
   *                  that can be trusted as accurate (like an envelope).
   */
  @WorkerThread
  public static @NonNull Recipient externalPush(@NonNull Context context, @Nullable ACI aci, @Nullable String e164, boolean highTrust) {
    if (UuidUtil.UNKNOWN_UUID.equals(aci)) {
      throw new AssertionError();
    }

    RecipientDatabase db          = SignalDatabase.recipients();
    RecipientId       recipientId = db.getAndPossiblyMerge(aci, e164, highTrust);

    Recipient resolved = resolved(recipientId);

    if (highTrust && !resolved.isRegistered() && aci != null) {
      Log.w(TAG, "External high-trust push was locally marked unregistered. Marking as registered.");
      db.markRegistered(recipientId, aci);
    } else if (highTrust && !resolved.isRegistered()) {
      Log.w(TAG, "External high-trust push was locally marked unregistered, but we don't have a UUID, so we can't do anything.", new Throwable());
    }

    return resolved;
  }

  /**
   * A safety wrapper around {@link #external(Context, String)} for when you know you're using an
   * identifier for a system contact, and therefore always want to prevent interpreting it as a
   * UUID. This will crash if given a UUID.
   *
   * (This may seem strange, but apparently some devices are returning valid UUIDs for contacts)
   */
  @WorkerThread
  public static @NonNull Recipient externalContact(@NonNull Context context, @NonNull String identifier) {
    RecipientDatabase db = SignalDatabase.recipients();
    RecipientId       id = null;

    if (UuidUtil.isUuid(identifier)) {
      throw new AssertionError("UUIDs are not valid system contact identifiers!");
    } else if (NumberUtil.isValidEmail(identifier)) {
      id = db.getOrInsertFromEmail(identifier);
    } else {
      id = db.getOrInsertFromE164(identifier);
    }

    return Recipient.resolved(id);
  }

  /**
   * A version of {@link #external(Context, String)} that should be used when you know the
   * identifier is a groupId.
   *
   * Important: This will throw an exception if the groupId you're using could have been migrated.
   * If you're dealing with inbound data, you should be using
   * {@link #externalPossiblyMigratedGroup(Context, GroupId)}, or checking the database before
   * calling this method.
   */
  @WorkerThread
  public static @NonNull Recipient externalGroupExact(@NonNull Context context, @NonNull GroupId groupId) {
    return Recipient.resolved(SignalDatabase.recipients().getOrInsertFromGroupId(groupId));
  }

  /**
   * Will give you one of:
   * - The recipient that matches the groupId specified exactly
   * - The recipient whose V1 ID would map to the provided V2 ID
   * - The recipient whose V2 ID would be derived from the provided V1 ID
   * - A newly-created recipient for the provided ID if none of the above match
   *
   * Important: You could get back a recipient with a different groupId than the one you provided.
   * You should be very cautious when using the groupId on the returned recipient.
   */
  @WorkerThread
  public static @NonNull Recipient externalPossiblyMigratedGroup(@NonNull Context context, @NonNull GroupId groupId) {
    return Recipient.resolved(SignalDatabase.recipients().getOrInsertFromPossiblyMigratedGroupId(groupId));
  }

  /**
   * Returns a fully-populated {@link Recipient} based off of a string identifier, creating one in
   * the database if necessary. The identifier may be a uuid, phone number, email,
   * or serialized groupId.
   *
   * If the identifier is a UUID of a Signal user, prefer using
   * {@link #externalPush(Context, ACI, String, boolean)} or its overload, as this will let us associate
   * the phone number with the recipient.
   */
  @WorkerThread
  public static @NonNull Recipient external(@NonNull Context context, @NonNull String identifier) {
    Preconditions.checkNotNull(identifier, "Identifier cannot be null!");

    RecipientDatabase db = SignalDatabase.recipients();
    RecipientId       id = null;

    if (UuidUtil.isUuid(identifier)) {
      ACI uuid = ACI.parseOrThrow(identifier);
      id = db.getOrInsertFromAci(uuid);
    } else if (GroupId.isEncodedGroup(identifier)) {
      id = db.getOrInsertFromGroupId(GroupId.parseOrThrow(identifier));
    } else if (NumberUtil.isValidEmail(identifier)) {
      id = db.getOrInsertFromEmail(identifier);
    } else {
      String e164 = PhoneNumberFormatter.get(context).format(identifier);
      id = db.getOrInsertFromE164(e164);
    }

    return Recipient.resolved(id);
  }

  public static @NonNull Recipient self() {
    return ApplicationDependencies.getRecipientCache().getSelf();
  }

  Recipient(@NonNull RecipientId id) {
    this.id                          = id;
    this.resolving                   = true;
    this.aci                         = null;
    this.pni                         = null;
    this.username                    = null;
    this.e164                        = null;
    this.email                       = null;
    this.groupId                     = null;
    this.participants                = Collections.emptyList();
    this.groupAvatarId               = Optional.absent();
    this.isSelf                      = false;
    this.blocked                     = false;
    this.muteUntil                   = 0;
    this.messageVibrate              = VibrateState.DEFAULT;
    this.callVibrate                 = VibrateState.DEFAULT;
    this.messageRingtone             = null;
    this.callRingtone                = null;
    this.insightsBannerTier          = InsightsBannerTier.TIER_TWO;
    this.defaultSubscriptionId       = Optional.absent();
    this.expireMessages              = 0;
    this.registered                  = RegisteredState.UNKNOWN;
    this.profileKey                  = null;
    this.profileKeyCredential        = null;
    this.groupName                   = null;
    this.systemContactPhoto          = null;
    this.customLabel                 = null;
    this.contactUri                  = null;
    this.signalProfileName           = ProfileName.EMPTY;
    this.profileAvatar               = null;
    this.hasProfileImage             = false;
    this.profileSharing              = false;
    this.lastProfileFetch            = 0;
    this.notificationChannel         = null;
    this.unidentifiedAccessMode      = UnidentifiedAccessMode.DISABLED;
    this.forceSmsSelection           = false;
    this.groupsV2Capability          = Capability.UNKNOWN;
    this.groupsV1MigrationCapability = Capability.UNKNOWN;
    this.senderKeyCapability         = Capability.UNKNOWN;
    this.announcementGroupCapability = Capability.UNKNOWN;
    this.changeNumberCapability      = Capability.UNKNOWN;
    this.storageId                   = null;
    this.mentionSetting              = MentionSetting.ALWAYS_NOTIFY;
    this.wallpaper                   = null;
    this.chatColors                  = null;
    this.avatarColor                 = AvatarColor.UNKNOWN;
    this.about                       = null;
    this.aboutEmoji                  = null;
    this.systemProfileName           = ProfileName.EMPTY;
    this.systemContactName           = null;
    this.extras                      = Optional.absent();
    this.hasGroupsInCommon           = false;
    this.badges                      = Collections.emptyList();
  }

  public Recipient(@NonNull RecipientId id, @NonNull RecipientDetails details, boolean resolved) {
    this.id                          = id;
    this.resolving                   = !resolved;
    this.aci                         = details.aci;
    this.pni                         = details.pni;
    this.username                    = details.username;
    this.e164                        = details.e164;
    this.email                       = details.email;
    this.groupId                     = details.groupId;
    this.participants                = details.participants;
    this.groupAvatarId               = details.groupAvatarId;
    this.isSelf                      = details.isSelf;
    this.blocked                     = details.blocked;
    this.muteUntil                   = details.mutedUntil;
    this.messageVibrate              = details.messageVibrateState;
    this.callVibrate                 = details.callVibrateState;
    this.messageRingtone             = details.messageRingtone;
    this.callRingtone                = details.callRingtone;
    this.insightsBannerTier          = details.insightsBannerTier;
    this.defaultSubscriptionId       = details.defaultSubscriptionId;
    this.expireMessages              = details.expireMessages;
    this.registered                  = details.registered;
    this.profileKey                  = details.profileKey;
    this.profileKeyCredential        = details.profileKeyCredential;
    this.groupName                   = details.groupName;
    this.systemContactPhoto          = details.systemContactPhoto;
    this.customLabel                 = details.customLabel;
    this.contactUri                  = details.contactUri;
    this.signalProfileName           = details.profileName;
    this.profileAvatar               = details.profileAvatar;
    this.hasProfileImage             = details.hasProfileImage;
    this.profileSharing              = details.profileSharing;
    this.lastProfileFetch            = details.lastProfileFetch;
    this.notificationChannel         = details.notificationChannel;
    this.unidentifiedAccessMode      = details.unidentifiedAccessMode;
    this.forceSmsSelection           = details.forceSmsSelection;
    this.groupsV2Capability          = details.groupsV2Capability;
    this.groupsV1MigrationCapability = details.groupsV1MigrationCapability;
    this.senderKeyCapability         = details.senderKeyCapability;
    this.announcementGroupCapability = details.announcementGroupCapability;
    this.changeNumberCapability      = details.changeNumberCapability;
    this.storageId                   = details.storageId;
    this.mentionSetting              = details.mentionSetting;
    this.wallpaper                   = details.wallpaper;
    this.chatColors                  = details.chatColors;
    this.avatarColor                 = details.avatarColor;
    this.about                       = details.about;
    this.aboutEmoji                  = details.aboutEmoji;
    this.systemProfileName           = details.systemProfileName;
    this.systemContactName           = details.systemContactName;
    this.extras                      = details.extras;
    this.hasGroupsInCommon           = details.hasGroupsInCommon;
    this.badges                      = details.badges;
  }

  public @NonNull RecipientId getId() {
    return id;
  }

  public boolean isSelf() {
    return isSelf;
  }

  public @Nullable Uri getContactUri() {
    return contactUri;
  }

  public @Nullable String getGroupName(@NonNull Context context) {
    if (groupId != null && Util.isEmpty(this.groupName)) {
      List<Recipient> others = participants.stream()
                                           .filter(r -> !r.isSelf())
                                           .limit(MAX_MEMBER_NAMES)
                                           .collect(Collectors.toList());

      Map<String, Integer> shortNameCounts = new HashMap<>();

      for (Recipient participant : others) {
        String shortName = participant.getShortDisplayName(context);
        int    count     = Objects.requireNonNull(shortNameCounts.getOrDefault(shortName, 0));

        shortNameCounts.put(shortName, count + 1);
      }

      List<String> names = new LinkedList<>();

      for (Recipient participant : others) {
        String shortName = participant.getShortDisplayName(context);
        int    count     = Objects.requireNonNull(shortNameCounts.getOrDefault(shortName, 0));

        if (count <= 1) {
          names.add(shortName);
        } else {
          names.add(participant.getDisplayName(context));
        }
      }

      if (participants.stream().anyMatch(Recipient::isSelf)) {
        names.add(context.getString(R.string.Recipient_you));
      }

      return Util.join(names, ", ");
    } else {
      return this.groupName;
    }
  }

  public boolean hasName() {
    return groupName != null;
  }

  /**
   * False iff it {@link #getDisplayName} would fall back to e164, email or unknown.
   */
  public boolean hasAUserSetDisplayName(@NonNull Context context) {
    return !TextUtils.isEmpty(getGroupName(context))             ||
           !TextUtils.isEmpty(systemContactName)                 ||
           !TextUtils.isEmpty(getProfileName().toString());
  }

  public @NonNull String getDisplayName(@NonNull Context context) {
    String name = getGroupName(context);

    if (Util.isEmpty(name)) {
      name = systemContactName;
    }

    if (Util.isEmpty(name)) {
      name = getProfileName().toString();
    }

    if (Util.isEmpty(name) && !Util.isEmpty(e164)) {
      name = PhoneNumberFormatter.prettyPrint(e164);
    }

    if (Util.isEmpty(name)) {
      name = email;
    }

    if (Util.isEmpty(name)) {
      name = context.getString(R.string.Recipient_unknown);
    }

    return StringUtil.isolateBidi(name);
  }

  public @NonNull String getDisplayNameOrUsername(@NonNull Context context) {
    String name = getGroupName(context);

    if (Util.isEmpty(name)) {
      name = systemContactName;
    }

    if (Util.isEmpty(name)) {
      name = StringUtil.isolateBidi(getProfileName().toString());
    }

    if (Util.isEmpty(name) && !Util.isEmpty(e164)) {
      name = PhoneNumberFormatter.prettyPrint(e164);
    }

    if (Util.isEmpty(name)) {
      name = StringUtil.isolateBidi(email);
    }

    if (Util.isEmpty(name)) {
      name = StringUtil.isolateBidi(username);
    }

    if (Util.isEmpty(name)) {
      name = StringUtil.isolateBidi(context.getString(R.string.Recipient_unknown));
    }

    return name;
  }

  public @NonNull String getMentionDisplayName(@NonNull Context context) {
    String name = isSelf ? getProfileName().toString() : getGroupName(context);
    name = StringUtil.isolateBidi(name);

    if (Util.isEmpty(name)) {
      name = isSelf ? getGroupName(context) : systemContactName;
      name = StringUtil.isolateBidi(name);
    }

    if (Util.isEmpty(name)) {
      name = isSelf ? getGroupName(context) : getProfileName().toString();
      name = StringUtil.isolateBidi(name);
    }

    if (Util.isEmpty(name) && !Util.isEmpty(e164)) {
      name = PhoneNumberFormatter.prettyPrint(e164);
    }

    if (Util.isEmpty(name)) {
      name = StringUtil.isolateBidi(email);
    }

    if (Util.isEmpty(name)) {
      name = StringUtil.isolateBidi(context.getString(R.string.Recipient_unknown));
    }

    return name;
  }

  public @NonNull String getShortDisplayName(@NonNull Context context) {
    String name = Util.getFirstNonEmpty(getGroupName(context),
                                        getSystemProfileName().getGivenName(),
                                        getProfileName().getGivenName(),
                                        getDisplayName(context));

    return StringUtil.isolateBidi(name);
  }

  public @NonNull String getShortDisplayNameIncludingUsername(@NonNull Context context) {
    String name = Util.getFirstNonEmpty(getGroupName(context),
                                        getSystemProfileName().getGivenName(),
                                        getProfileName().getGivenName(),
                                        getDisplayName(context),
                                        getUsername().orNull());

    return StringUtil.isolateBidi(name);
  }

  public @NonNull Optional<ACI> getAci() {
    return Optional.fromNullable(aci);
  }

  public @NonNull Optional<PNI> getPni() {
    return Optional.fromNullable(pni);
  }

  public @NonNull Optional<String> getUsername() {
    if (FeatureFlags.usernames()) {
      return Optional.fromNullable(username);
    } else {
      return Optional.absent();
    }
  }

  public @NonNull Optional<String> getE164() {
    return Optional.fromNullable(e164);
  }

  public @NonNull Optional<String> getEmail() {
    return Optional.fromNullable(email);
  }

  public @NonNull Optional<GroupId> getGroupId() {
    return Optional.fromNullable(groupId);
  }

  public @NonNull Optional<String> getSmsAddress() {
    return Optional.fromNullable(e164).or(Optional.fromNullable(email));
  }

  public @NonNull ACI requireAci() {
    ACI resolved = resolving ? resolve().aci : aci;

    if (resolved == null) {
      throw new MissingAddressError(id);
    }

    return resolved;
  }


  public @NonNull String requireE164() {
    String resolved = resolving ? resolve().e164 : e164;

    if (resolved == null) {
      throw new MissingAddressError(id);
    }

    return resolved;
  }

  public @NonNull String requireEmail() {
    String resolved = resolving ? resolve().email : email;

    if (resolved == null) {
      throw new MissingAddressError(id);
    }

    return resolved;
  }

  public @NonNull String requireSmsAddress() {
    Recipient recipient = resolving ? resolve() : this;

    if (recipient.getE164().isPresent()) {
      return recipient.getE164().get();
    } else if (recipient.getEmail().isPresent()) {
      return recipient.getEmail().get();
    } else {
      throw new MissingAddressError(id);
    }
  }

  public boolean hasSmsAddress() {
    return getE164().or(getEmail()).isPresent();
  }

  public boolean hasE164() {
    return getE164().isPresent();
  }

  public boolean hasAci() {
    return getAci().isPresent();
  }

  public boolean isAciOnly() {
    return hasAci() && !hasSmsAddress();
  }

  public @NonNull GroupId requireGroupId() {
    GroupId resolved = resolving ? resolve().groupId : groupId;

    if (resolved == null) {
      throw new MissingAddressError(id);
    }

    return resolved;
  }

  public boolean hasServiceIdentifier() {
    return aci != null || e164 != null;
  }

  /**
   * @return A string identifier able to be used with the Signal service. Prefers ACI, and if not
   * available, will return an E164 number.
   */
  public @NonNull String requireServiceId() {
    Recipient resolved = resolving ? resolve() : this;

    if (resolved.getAci().isPresent()) {
      return resolved.requireAci().toString();
    } else {
      return getE164().get();
    }
  }

  /**
   * @return A single string to represent the recipient, in order of precedence:
   *
   * Group ID > ACI > Phone > Email
   */
  public @NonNull String requireStringId() {
    Recipient resolved = resolving ? resolve() : this;

    if (resolved.isGroup()) {
      return resolved.requireGroupId().toString();
    } else if (resolved.getAci().isPresent()) {
      return resolved.requireAci().toString();
    }

    return requireSmsAddress();
  }

  public Optional<Integer> getDefaultSubscriptionId() {
    return defaultSubscriptionId;
  }

  public @NonNull ProfileName getProfileName() {
    return signalProfileName;
  }

  private @NonNull ProfileName getSystemProfileName() {
    return systemProfileName;
  }

  public @Nullable String getProfileAvatar() {
    return profileAvatar;
  }

  public boolean isProfileSharing() {
    return profileSharing;
  }

  public long getLastProfileFetchTime() {
    return lastProfileFetch;
  }

  public boolean isGroup() {
    return resolve().groupId != null;
  }

  private boolean isGroupInternal() {
    return groupId != null;
  }

  public boolean isMmsGroup() {
    GroupId groupId = resolve().groupId;
    return groupId != null && groupId.isMms();
  }

  public boolean isPushGroup() {
    GroupId groupId = resolve().groupId;
    return groupId != null && groupId.isPush();
  }

  public boolean isPushV1Group() {
    GroupId groupId = resolve().groupId;
    return groupId != null && groupId.isV1();
  }

  public boolean isPushV2Group() {
    GroupId groupId = resolve().groupId;
    return groupId != null && groupId.isV2();
  }

  public boolean isActiveGroup() {
    return Stream.of(getParticipants()).anyMatch(Recipient::isSelf);
  }

  public @NonNull List<Recipient> getParticipants() {
    return new ArrayList<>(participants);
  }

  public @NonNull Drawable getFallbackContactPhotoDrawable(Context context, boolean inverted) {
    return getFallbackContactPhotoDrawable(context, inverted, DEFAULT_FALLBACK_PHOTO_PROVIDER, AvatarUtil.UNDEFINED_SIZE);
  }

  public @NonNull Drawable getSmallFallbackContactPhotoDrawable(Context context, boolean inverted) {
    return getSmallFallbackContactPhotoDrawable(context, inverted, DEFAULT_FALLBACK_PHOTO_PROVIDER);
  }

  public @NonNull Drawable getFallbackContactPhotoDrawable(Context context, boolean inverted, @Nullable FallbackPhotoProvider fallbackPhotoProvider, int targetSize) {
    return getFallbackContactPhoto(Util.firstNonNull(fallbackPhotoProvider, DEFAULT_FALLBACK_PHOTO_PROVIDER), targetSize).asDrawable(context, avatarColor, inverted);
  }

  public @NonNull Drawable getSmallFallbackContactPhotoDrawable(Context context, boolean inverted, @Nullable FallbackPhotoProvider fallbackPhotoProvider) {
    return getSmallFallbackContactPhotoDrawable(context, inverted, fallbackPhotoProvider, AvatarUtil.UNDEFINED_SIZE);
  }

  public @NonNull Drawable getSmallFallbackContactPhotoDrawable(Context context, boolean inverted, @Nullable FallbackPhotoProvider fallbackPhotoProvider, int targetSize) {
    return getFallbackContactPhoto(Util.firstNonNull(fallbackPhotoProvider, DEFAULT_FALLBACK_PHOTO_PROVIDER), targetSize).asSmallDrawable(context, avatarColor, inverted);
  }

  public @NonNull FallbackContactPhoto getFallbackContactPhoto() {
    return getFallbackContactPhoto(DEFAULT_FALLBACK_PHOTO_PROVIDER);
  }

  public @NonNull FallbackContactPhoto getFallbackContactPhoto(@NonNull FallbackPhotoProvider fallbackPhotoProvider) {
    return getFallbackContactPhoto(fallbackPhotoProvider, AvatarUtil.UNDEFINED_SIZE);
  }

  public @NonNull FallbackContactPhoto getFallbackContactPhoto(@NonNull FallbackPhotoProvider fallbackPhotoProvider, int targetSize) {
    if      (isSelf)                                return fallbackPhotoProvider.getPhotoForLocalNumber();
    else if (isResolving())                         return fallbackPhotoProvider.getPhotoForResolvingRecipient();
    else if (isGroupInternal())                     return fallbackPhotoProvider.getPhotoForGroup();
    else if (isGroup())                             return fallbackPhotoProvider.getPhotoForGroup();
    else if (!TextUtils.isEmpty(groupName))         return fallbackPhotoProvider.getPhotoForRecipientWithName(groupName, targetSize);
    else if (!TextUtils.isEmpty(systemContactName)) return fallbackPhotoProvider.getPhotoForRecipientWithName(systemContactName, targetSize);
    else if (!signalProfileName.isEmpty())          return fallbackPhotoProvider.getPhotoForRecipientWithName(signalProfileName.toString(), targetSize);
    else                                            return fallbackPhotoProvider.getPhotoForRecipientWithoutName();
  }

  public @Nullable ContactPhoto getContactPhoto() {
    if      (isSelf)                                                                             return null;
    else if (isGroupInternal() && groupAvatarId.isPresent())                                     return new GroupRecordContactPhoto(groupId, groupAvatarId.get());
    else if (systemContactPhoto != null && SignalStore.settings().isPreferSystemContactPhotos()) return new SystemContactPhoto(id, systemContactPhoto, 0);
    else if (profileAvatar != null && hasProfileImage)                                           return new ProfileContactPhoto(this, profileAvatar);
    else if (systemContactPhoto != null)                                                         return new SystemContactPhoto(id, systemContactPhoto, 0);
    else                                                                                         return null;
  }

  public @Nullable Uri getMessageRingtone() {
    if (messageRingtone != null && messageRingtone.getScheme() != null && messageRingtone.getScheme().startsWith("file")) {
      return null;
    }

    return messageRingtone;
  }

  public @Nullable Uri getCallRingtone() {
    if (callRingtone != null && callRingtone.getScheme() != null && callRingtone.getScheme().startsWith("file")) {
      return null;
    }

    return callRingtone;
  }

  public boolean isMuted() {
    return System.currentTimeMillis() <= muteUntil;
  }

  public long getMuteUntil() {
    return muteUntil;
  }

  public boolean isBlocked() {
    return blocked;
  }

  public @NonNull VibrateState getMessageVibrate() {
    return messageVibrate;
  }

  public @NonNull VibrateState getCallVibrate() {
    return callVibrate;
  }

  public int getExpiresInSeconds() {
    return expireMessages;
  }

  public boolean hasSeenFirstInviteReminder() {
    return insightsBannerTier.seen(InsightsBannerTier.TIER_ONE);
  }

  public boolean hasSeenSecondInviteReminder() {
    return insightsBannerTier.seen(InsightsBannerTier.TIER_TWO);
  }

  public @NonNull RegisteredState getRegistered() {
    if      (isPushGroup()) return RegisteredState.REGISTERED;
    else if (isMmsGroup())  return RegisteredState.NOT_REGISTERED;

    return registered;
  }

  public boolean isRegistered() {
    return registered == RegisteredState.REGISTERED || isPushGroup();
  }

  public boolean isMaybeRegistered() {
    return registered != RegisteredState.NOT_REGISTERED || isPushGroup();
  }

  public boolean isUnregistered() {
    return registered == RegisteredState.NOT_REGISTERED && !isPushGroup();
  }

  public @Nullable String getNotificationChannel() {
    return !NotificationChannels.supported() ? null : notificationChannel;
  }

  public boolean isForceSmsSelection() {
    return forceSmsSelection;
  }

  public @NonNull Capability getGroupsV2Capability() {
    return groupsV2Capability;
  }

  public @NonNull Capability getGroupsV1MigrationCapability() {
    return groupsV1MigrationCapability;
  }

  public @NonNull Capability getSenderKeyCapability() {
    return senderKeyCapability;
  }

  public @NonNull Capability getAnnouncementGroupCapability() {
    return announcementGroupCapability;
  }

  public @NonNull Capability getChangeNumberCapability() {
    return changeNumberCapability;
  }

  /**
   * True if this recipient supports the message retry system, or false if we should use the legacy session reset system.
   */
  public boolean supportsMessageRetries() {
    return getSenderKeyCapability() == Capability.SUPPORTED;
  }

  public @Nullable byte[] getProfileKey() {
    return profileKey;
  }

  public @Nullable ProfileKeyCredential getProfileKeyCredential() {
    return profileKeyCredential;
  }

  public boolean hasProfileKeyCredential() {
    return profileKeyCredential != null;
  }

  public @Nullable byte[] getStorageServiceId() {
    return storageId;
  }

  public @NonNull UnidentifiedAccessMode getUnidentifiedAccessMode() {
    return unidentifiedAccessMode;
  }

  public @Nullable ChatWallpaper getWallpaper() {
    if (wallpaper != null) {
      return wallpaper;
    } else {
      return SignalStore.wallpaper().getWallpaper();
    }
  }

  public boolean hasOwnWallpaper() {
    return wallpaper != null;
  }

  /**
   * A cheap way to check if wallpaper is set without doing any unnecessary proto parsing.
   */
  public boolean hasWallpaper() {
    return wallpaper != null || SignalStore.wallpaper().hasWallpaperSet();
  }

  public boolean hasOwnChatColors() {
    return chatColors != null;
  }

  public @NonNull ChatColors getChatColors() {
    if (chatColors != null && !(chatColors.getId() instanceof ChatColors.Id.Auto)) {
      return chatColors;
    } if (chatColors != null) {
      return getAutoChatColor();
    } else {
      ChatColors global = SignalStore.chatColorsValues().getChatColors();
      if (global != null && !(global.getId() instanceof ChatColors.Id.Auto)) {
        return global;
      } else {
        return getAutoChatColor();
      }
    }
  }

  private @NonNull ChatColors getAutoChatColor() {
    if (getWallpaper() != null) {
      return getWallpaper().getAutoChatColors();
    } else {
      return ChatColorsPalette.Bubbles.getDefault().withId(ChatColors.Id.Auto.INSTANCE);
    }
  }

  public @NonNull AvatarColor getAvatarColor() {
    return avatarColor;
  }

  public boolean isSystemContact() {
    return contactUri != null;
  }

  public @Nullable String getAbout() {
    return about;
  }

  public @Nullable String getAboutEmoji() {
    return aboutEmoji;
  }

  public @NonNull List<Badge> getBadges() {
    return FeatureFlags.displayDonorBadges() || isSelf() ? badges : Collections.emptyList();
  }

  public @Nullable Badge getFeaturedBadge() {
    if (getBadges().isEmpty()) {
      return null;
    } else {
      return getBadges().get(0);
    }
  }

  public @Nullable String getCombinedAboutAndEmoji() {
    if (!Util.isEmpty(aboutEmoji)) {
      if (!Util.isEmpty(about)) {
        return aboutEmoji + " " + about;
      } else {
        return aboutEmoji;
      }
    } else if (!Util.isEmpty(about)) {
      return about;
    } else {
      return null;
    }
  }

  public boolean shouldBlurAvatar() {
    boolean showOverride = false;
    if (extras.isPresent()) {
      showOverride = extras.get().manuallyShownAvatar();
    }
    return !showOverride && !isSelf() && !isProfileSharing() && !isSystemContact() && !hasGroupsInCommon && isRegistered();
  }

  public boolean hasGroupsInCommon() {
    return hasGroupsInCommon;
  }

  /**
   * If this recipient is missing crucial data, this will return a populated copy. Otherwise it
   * returns itself.
   */
  public @NonNull Recipient resolve() {
    if (resolving) {
      return live().resolve();
    } else {
      return this;
    }
  }

  public boolean isResolving() {
    return resolving;
  }

  /**
   * Forces retrieving a fresh copy of the recipient, regardless of its state.
   */
  public @NonNull Recipient fresh() {
    return live().resolve();
  }

  public @NonNull LiveRecipient live() {
    return ApplicationDependencies.getRecipientCache().getLive(id);
  }

  public @NonNull MentionSetting getMentionSetting() {
    return mentionSetting;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Recipient recipient = (Recipient) o;
    return id.equals(recipient.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  public enum Capability {
    UNKNOWN(0),
    SUPPORTED(1),
    NOT_SUPPORTED(2);

    private final int value;

    Capability(int value) {
      this.value = value;
    }

    public int serialize() {
      return value;
    }

    public static Capability deserialize(int value) {
      switch (value) {
        case 0:  return UNKNOWN;
        case 1:  return SUPPORTED;
        case 2:  return NOT_SUPPORTED;
        default: throw new IllegalArgumentException();
      }
    }

    public static Capability fromBoolean(boolean supported) {
      return supported ? SUPPORTED : NOT_SUPPORTED;
    }
  }

  public static final class Extras {
    private final RecipientExtras recipientExtras;

    public static @Nullable Extras from(@Nullable RecipientExtras recipientExtras) {
      if (recipientExtras != null) {
        return new Extras(recipientExtras);
      } else {
        return null;
      }
    }

    private Extras(@NonNull RecipientExtras extras) {
      this.recipientExtras = extras;
    }

    public boolean manuallyShownAvatar() {
      return recipientExtras.getManuallyShownAvatar();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      final Extras that = (Extras) o;
      return manuallyShownAvatar() == that.manuallyShownAvatar();
    }

    @Override
    public int hashCode() {
      return Objects.hash(manuallyShownAvatar());
    }
  }

  public boolean hasSameContent(@NonNull Recipient other) {
    return Objects.equals(id, other.id) &&
           resolving == other.resolving &&
           isSelf == other.isSelf &&
           blocked == other.blocked &&
           muteUntil == other.muteUntil &&
           expireMessages == other.expireMessages &&
           hasProfileImage == other.hasProfileImage &&
           profileSharing == other.profileSharing &&
           lastProfileFetch == other.lastProfileFetch &&
           forceSmsSelection == other.forceSmsSelection &&
           Objects.equals(id, other.id) &&
           Objects.equals(aci, other.aci) &&
           Objects.equals(username, other.username) &&
           Objects.equals(e164, other.e164) &&
           Objects.equals(email, other.email) &&
           Objects.equals(groupId, other.groupId) &&
           allContentsAreTheSame(participants, other.participants) &&
           Objects.equals(groupAvatarId, other.groupAvatarId) &&
           messageVibrate == other.messageVibrate &&
           callVibrate == other.callVibrate &&
           Objects.equals(messageRingtone, other.messageRingtone) &&
           Objects.equals(callRingtone, other.callRingtone) &&
           Objects.equals(defaultSubscriptionId, other.defaultSubscriptionId) &&
           registered == other.registered &&
           Arrays.equals(profileKey, other.profileKey) &&
           Objects.equals(profileKeyCredential, other.profileKeyCredential) &&
           Objects.equals(groupName, other.groupName) &&
           Objects.equals(systemContactPhoto, other.systemContactPhoto) &&
           Objects.equals(customLabel, other.customLabel) &&
           Objects.equals(contactUri, other.contactUri) &&
           Objects.equals(signalProfileName, other.signalProfileName) &&
           Objects.equals(systemProfileName, other.systemProfileName) &&
           Objects.equals(profileAvatar, other.profileAvatar) &&
           Objects.equals(notificationChannel, other.notificationChannel) &&
           unidentifiedAccessMode == other.unidentifiedAccessMode &&
           groupsV2Capability == other.groupsV2Capability &&
           groupsV1MigrationCapability == other.groupsV1MigrationCapability &&
           insightsBannerTier == other.insightsBannerTier &&
           Arrays.equals(storageId, other.storageId) &&
           mentionSetting == other.mentionSetting &&
           Objects.equals(wallpaper, other.wallpaper) &&
           Objects.equals(chatColors, other.chatColors) &&
           Objects.equals(avatarColor, other.avatarColor) &&
           Objects.equals(about, other.about) &&
           Objects.equals(aboutEmoji, other.aboutEmoji) &&
           Objects.equals(extras, other.extras) &&
           hasGroupsInCommon == other.hasGroupsInCommon &&
           Objects.equals(badges, other.badges);
  }

  private static boolean allContentsAreTheSame(@NonNull List<Recipient> a, @NonNull List<Recipient> b) {
    if (a.size() != b.size()) {
      return false;
    }

    for (int i = 0, len = a.size(); i < len; i++) {
      if (!a.get(i).hasSameContent(b.get(i))) {
        return false;
      }
    }

    return true;
  }


  public static class FallbackPhotoProvider {
    public @NonNull FallbackContactPhoto getPhotoForLocalNumber() {
      return new ResourceContactPhoto(R.drawable.ic_note_34, R.drawable.ic_note_24);
    }

    public @NonNull FallbackContactPhoto getPhotoForResolvingRecipient() {
      return new TransparentContactPhoto();
    }

    public @NonNull FallbackContactPhoto getPhotoForGroup() {
      return new ResourceContactPhoto(R.drawable.ic_group_outline_34, R.drawable.ic_group_outline_20, R.drawable.ic_group_outline_48);
    }

    public @NonNull FallbackContactPhoto getPhotoForRecipientWithName(String name, int targetSize) {
      return new GeneratedContactPhoto(name, R.drawable.ic_profile_outline_40, targetSize);
    }

    public @NonNull FallbackContactPhoto getPhotoForRecipientWithoutName() {
      return new ResourceContactPhoto(R.drawable.ic_profile_outline_40, R.drawable.ic_profile_outline_20, R.drawable.ic_profile_outline_48);
    }
  }

  private static class MissingAddressError extends AssertionError {
    MissingAddressError(@NonNull RecipientId recipientId) {
      super("Missing address for " + recipientId.serialize());
    }
  }
}
