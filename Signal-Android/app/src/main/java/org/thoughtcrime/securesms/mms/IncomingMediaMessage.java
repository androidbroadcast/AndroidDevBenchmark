package org.thoughtcrime.securesms.mms;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.PointerAttachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.database.model.Mention;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceGroupContext;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class IncomingMediaMessage {

  private final RecipientId from;
  private final GroupId     groupId;
  private final String      body;
  private final boolean     push;
  private final long        sentTimeMillis;
  private final long serverTimeMillis;
  private final long receivedTimeMillis;
  private final int  subscriptionId;
  private final long        expiresIn;
  private final boolean     expirationUpdate;
  private final QuoteModel  quote;
  private final boolean     unidentified;
  private final boolean     viewOnce;
  private final String      serverGuid;

  private final List<Attachment>  attachments    = new LinkedList<>();
  private final List<Contact>     sharedContacts = new LinkedList<>();
  private final List<LinkPreview> linkPreviews   = new LinkedList<>();
  private final List<Mention>     mentions       = new LinkedList<>();

  public IncomingMediaMessage(@NonNull RecipientId from,
                              Optional<GroupId> groupId,
                              String body,
                              long sentTimeMillis,
                              long serverTimeMillis,
                              long receivedTimeMillis,
                              List<Attachment> attachments,
                              int subscriptionId,
                              long expiresIn,
                              boolean expirationUpdate,
                              boolean viewOnce,
                              boolean unidentified,
                              Optional<List<Contact>> sharedContacts)
  {
    this.from               = from;
    this.groupId            = groupId.orNull();
    this.sentTimeMillis     = sentTimeMillis;
    this.serverTimeMillis   = serverTimeMillis;
    this.receivedTimeMillis = receivedTimeMillis;
    this.body               = body;
    this.push               = false;
    this.subscriptionId     = subscriptionId;
    this.expiresIn          = expiresIn;
    this.expirationUpdate   = expirationUpdate;
    this.viewOnce           = viewOnce;
    this.quote              = null;
    this.unidentified       = unidentified;
    this.serverGuid         = null;

    this.attachments.addAll(attachments);
    this.sharedContacts.addAll(sharedContacts.or(Collections.emptyList()));

  }

  public IncomingMediaMessage(@NonNull RecipientId from,
                              long sentTimeMillis,
                              long serverTimeMillis,
                              long receivedTimeMillis,
                              int subscriptionId,
                              long expiresIn,
                              boolean expirationUpdate,
                              boolean viewOnce,
                              boolean unidentified,
                              Optional<String> body,
                              Optional<SignalServiceGroupContext> group,
                              Optional<List<SignalServiceAttachment>> attachments,
                              Optional<QuoteModel> quote,
                              Optional<List<Contact>> sharedContacts,
                              Optional<List<LinkPreview>> linkPreviews,
                              Optional<List<Mention>> mentions,
                              Optional<Attachment> sticker,
                              @Nullable String serverGuid)
  {
    this.push               = true;
    this.from               = from;
    this.sentTimeMillis     = sentTimeMillis;
    this.serverTimeMillis   = serverTimeMillis;
    this.receivedTimeMillis = receivedTimeMillis;
    this.body               = body.orNull();
    this.subscriptionId     = subscriptionId;
    this.expiresIn          = expiresIn;
    this.expirationUpdate   = expirationUpdate;
    this.viewOnce           = viewOnce;
    this.quote              = quote.orNull();
    this.unidentified       = unidentified;

    if (group.isPresent()) this.groupId = GroupUtil.idFromGroupContextOrThrow(group.get());
    else                   this.groupId = null;

    this.attachments.addAll(PointerAttachment.forPointers(attachments));
    this.sharedContacts.addAll(sharedContacts.or(Collections.emptyList()));
    this.linkPreviews.addAll(linkPreviews.or(Collections.emptyList()));
    this.mentions.addAll(mentions.or(Collections.emptyList()));

    if (sticker.isPresent()) {
      this.attachments.add(sticker.get());
    }

    this.serverGuid       = serverGuid;
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public String getBody() {
    return body;
  }

  public List<Attachment> getAttachments() {
    return attachments;
  }

  public @NonNull RecipientId getFrom() {
    return from;
  }

  public GroupId getGroupId() {
    return groupId;
  }

  public boolean isPushMessage() {
    return push;
  }

  public boolean isExpirationUpdate() {
    return expirationUpdate;
  }

  public long getSentTimeMillis() {
    return sentTimeMillis;
  }

  public long getServerTimeMillis() {
    return serverTimeMillis;
  }

  public long getReceivedTimeMillis() {
    return receivedTimeMillis;
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  public boolean isViewOnce() {
    return viewOnce;
  }

  public boolean isGroupMessage() {
    return groupId != null;
  }

  public QuoteModel getQuote() {
    return quote;
  }

  public List<Contact> getSharedContacts() {
    return sharedContacts;
  }

  public List<LinkPreview> getLinkPreviews() {
    return linkPreviews;
  }

  public @NonNull List<Mention> getMentions() {
    return mentions;
  }

  public boolean isUnidentified() {
    return unidentified;
  }

  public @Nullable String getServerGuid() {
    return serverGuid;
  }
}
