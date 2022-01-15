/*
 * Copyright (C) 2013 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.contacts;

import android.accounts.Account;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.provider.ContactsContract.RawContacts;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Database to supply all types of contacts that TextSecure needs to know about
 *
 * @author Jake McGinty
 */
public class ContactsDatabase {

  private static final String TAG              = Log.tag(ContactsDatabase.class);
  private static final String CONTACT_MIMETYPE = "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact";
  private static final String CALL_MIMETYPE    = "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.call";
  private static final String SYNC             = "__TS";

  private final Context context;

  public ContactsDatabase(Context context) {
    this.context  = context;
  }

  public synchronized  void removeDeletedRawContacts(@NonNull Account account) {
    Uri currentContactsUri = RawContacts.CONTENT_URI.buildUpon()
                                                    .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                                                    .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
                                                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                                                    .build();

    String[] projection = new String[] {BaseColumns._ID, RawContacts.SYNC1};

    try (Cursor cursor = context.getContentResolver().query(currentContactsUri, projection, RawContacts.DELETED + " = ?", new String[] {"1"}, null)) {
      while (cursor != null && cursor.moveToNext()) {
        long rawContactId = cursor.getLong(0);
        Log.i(TAG, "Deleting raw contact: " + cursor.getString(1) + ", " + rawContactId);

        context.getContentResolver().delete(currentContactsUri, RawContacts._ID + " = ?", new String[] {String.valueOf(rawContactId)});
      }
    }
  }

  public synchronized void setRegisteredUsers(@NonNull Account account,
                                              @NonNull List<String> registeredAddressList,
                                              boolean remove)
      throws RemoteException, OperationApplicationException
  {
    Set<String>                         registeredAddressSet = new HashSet<>(registeredAddressList);
    ArrayList<ContentProviderOperation> operations           = new ArrayList<>();
    Map<String, SignalContact>          currentContacts      = getSignalRawContacts(account);
    List<List<String>>                  registeredChunks     = Util.chunk(registeredAddressList, 50);

    for (List<String> registeredChunk : registeredChunks) {
      for (String registeredAddress : registeredChunk) {
        if (!currentContacts.containsKey(registeredAddress)) {
          Optional<SystemContactInfo> systemContactInfo = getSystemContactInfo(registeredAddress);

          if (systemContactInfo.isPresent()) {
            Log.i(TAG, "Adding number: " + registeredAddress);
            addTextSecureRawContact(operations, account, systemContactInfo.get().number,
                                    systemContactInfo.get().name, systemContactInfo.get().id);
          }
        }
      }
      if (!operations.isEmpty()) {
        context.getContentResolver().applyBatch(ContactsContract.AUTHORITY, operations);
        operations.clear();
      }
    }

    for (Map.Entry<String, SignalContact> currentContactEntry : currentContacts.entrySet()) {
      if (!registeredAddressSet.contains(currentContactEntry.getKey())) {
        if (remove) {
          Log.i(TAG, "Removing number: " + currentContactEntry.getKey());
          removeTextSecureRawContact(operations, account, currentContactEntry.getValue().getId());
        }
      } else if (!currentContactEntry.getValue().isVoiceSupported()) {
        Log.i(TAG, "Adding voice support: " + currentContactEntry.getKey());
        addContactVoiceSupport(operations, currentContactEntry.getKey(), currentContactEntry.getValue().getId());
      } else if (!Util.isStringEquals(currentContactEntry.getValue().getRawDisplayName(),
                                      currentContactEntry.getValue().getAggregateDisplayName()))
      {
        Log.i(TAG, "Updating display name: " + currentContactEntry.getKey());
        updateDisplayName(operations, currentContactEntry.getValue().getAggregateDisplayName(), currentContactEntry.getValue().getId(), currentContactEntry.getValue().getDisplayNameSource());
      }
    }

    if (!operations.isEmpty()) {
      applyOperationsInBatches(context.getContentResolver(), ContactsContract.AUTHORITY, operations, 50);
    }
  }

  public @Nullable Cursor getNameDetails(long contactId) {
    String[] projection = new String[] { ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                                         ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                                         ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                                         ContactsContract.CommonDataKinds.StructuredName.PREFIX,
                                         ContactsContract.CommonDataKinds.StructuredName.SUFFIX,
                                         ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME };
    String   selection  = ContactsContract.Data.CONTACT_ID + " = ? AND " + ContactsContract.Data.MIMETYPE + " = ?";
    String[] args       = new String[] { String.valueOf(contactId), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE };

    return context.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                                              projection,
                                              selection,
                                              args,
                                              null);
  }

  public @Nullable String getOrganizationName(long contactId) {
    String[] projection = new String[] { ContactsContract.CommonDataKinds.Organization.COMPANY };
    String   selection  = ContactsContract.Data.CONTACT_ID + " = ? AND " + ContactsContract.Data.MIMETYPE + " = ?";
    String[] args       = new String[] { String.valueOf(contactId), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE };

    try (Cursor cursor = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                                                            projection,
                                                            selection,
                                                            args,
                                                            null))
    {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getString(0);
      }
    }

    return null;
  }

  public @Nullable Cursor getPhoneDetails(long contactId) {
    String[] projection = new String[] { ContactsContract.CommonDataKinds.Phone.NUMBER,
                                         ContactsContract.CommonDataKinds.Phone.TYPE,
                                         ContactsContract.CommonDataKinds.Phone.LABEL };
    String   selection  = ContactsContract.Data.CONTACT_ID + " = ? AND " + ContactsContract.Data.MIMETYPE + " = ?";
    String[] args       = new String[] { String.valueOf(contactId), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE };

    return context.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
        projection,
        selection,
        args,
        null);
  }

  public @Nullable Cursor getEmailDetails(long contactId) {
    String[] projection = new String[] { ContactsContract.CommonDataKinds.Email.ADDRESS,
                                         ContactsContract.CommonDataKinds.Email.TYPE,
                                         ContactsContract.CommonDataKinds.Email.LABEL };
    String   selection  = ContactsContract.Data.CONTACT_ID + " = ? AND " + ContactsContract.Data.MIMETYPE + " = ?";
    String[] args       = new String[] { String.valueOf(contactId), ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE };

    return context.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                                              projection,
                                              selection,
                                              args,
                                              null);
  }

  public @Nullable Cursor getPostalAddressDetails(long contactId) {
    String[] projection = new String[] { ContactsContract.CommonDataKinds.StructuredPostal.TYPE,
                                         ContactsContract.CommonDataKinds.StructuredPostal.LABEL,
                                         ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                                         ContactsContract.CommonDataKinds.StructuredPostal.POBOX,
                                         ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD,
                                         ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                                         ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                                         ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                                         ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY };
    String   selection  = ContactsContract.Data.CONTACT_ID + " = ? AND " + ContactsContract.Data.MIMETYPE + " = ?";
    String[] args       = new String[] { String.valueOf(contactId), ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE };

    return context.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                                              projection,
                                              selection,
                                              args,
                                              null);
  }

  public @Nullable Uri getAvatarUri(long contactId) {
    String[] projection = new String[] { ContactsContract.CommonDataKinds.Photo.PHOTO_URI };
    String   selection  = ContactsContract.Data.CONTACT_ID + " = ? AND " + ContactsContract.Data.MIMETYPE + " = ?";
    String[] args       = new String[] { String.valueOf(contactId), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE };

    try (Cursor cursor = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                                                            projection,
                                                            selection,
                                                            args,
                                                            null))
    {
      if (cursor != null && cursor.moveToFirst()) {
        String uri = cursor.getString(0);
        if (uri != null) {
          return Uri.parse(uri);
        }
      }
    }

    return null;
  }



  private void addContactVoiceSupport(List<ContentProviderOperation> operations,
                                      @NonNull String address, long rawContactId)
  {
    operations.add(ContentProviderOperation.newUpdate(RawContacts.CONTENT_URI)
                                           .withSelection(RawContacts._ID + " = ?", new String[] {String.valueOf(rawContactId)})
                                           .withValue(RawContacts.SYNC4, "true")
                                           .build());

    operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build())
                                           .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                                           .withValue(ContactsContract.Data.MIMETYPE, CALL_MIMETYPE)
                                           .withValue(ContactsContract.Data.DATA1, address)
                                           .withValue(ContactsContract.Data.DATA2, context.getString(R.string.app_name))
                                           .withValue(ContactsContract.Data.DATA3, context.getString(R.string.ContactsDatabase_signal_call_s, address))
                                           .withYieldAllowed(true)
                                           .build());
  }

  private void updateDisplayName(List<ContentProviderOperation> operations,
                                 @Nullable String displayName,
                                 long rawContactId, int displayNameSource)
  {
    Uri dataUri = ContactsContract.Data.CONTENT_URI.buildUpon()
                                                   .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                                                   .build();

    if (displayNameSource != ContactsContract.DisplayNameSources.STRUCTURED_NAME) {
      operations.add(ContentProviderOperation.newInsert(dataUri)
                                             .withValue(ContactsContract.CommonDataKinds.StructuredName.RAW_CONTACT_ID, rawContactId)
                                             .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                                             .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                                             .build());
    } else {
      operations.add(ContentProviderOperation.newUpdate(dataUri)
                                             .withSelection(ContactsContract.CommonDataKinds.StructuredName.RAW_CONTACT_ID + " = ? AND " + ContactsContract.Data.MIMETYPE + " = ?",
                                                            new String[] {String.valueOf(rawContactId), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE})
                                             .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                                             .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                                             .build());
    }
  }

  private void addTextSecureRawContact(List<ContentProviderOperation> operations,
                                       Account account, String e164number, String displayName,
                                       long aggregateId)
  {
    int index   = operations.size();
    Uri dataUri = ContactsContract.Data.CONTENT_URI.buildUpon()
                                                   .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                                                   .build();

    operations.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                                           .withValue(RawContacts.ACCOUNT_NAME, account.name)
                                           .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                                           .withValue(RawContacts.SYNC1, e164number)
                                           .withValue(RawContacts.SYNC4, String.valueOf(true))
                                           .build());

    operations.add(ContentProviderOperation.newInsert(dataUri)
                                           .withValueBackReference(ContactsContract.CommonDataKinds.StructuredName.RAW_CONTACT_ID, index)
                                           .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                                           .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                                           .build());

    operations.add(ContentProviderOperation.newInsert(dataUri)
                                           .withValueBackReference(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID, index)
                                           .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                                           .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, e164number)
                                           .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_OTHER)
                                           .withValue(ContactsContract.Data.SYNC2, SYNC)
                                           .build());

    operations.add(ContentProviderOperation.newInsert(dataUri)
                                           .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, index)
                                           .withValue(ContactsContract.Data.MIMETYPE, CONTACT_MIMETYPE)
                                           .withValue(ContactsContract.Data.DATA1, e164number)
                                           .withValue(ContactsContract.Data.DATA2, context.getString(R.string.app_name))
                                           .withValue(ContactsContract.Data.DATA3, context.getString(R.string.ContactsDatabase_message_s, e164number))
                                           .withYieldAllowed(true)
                                           .build());

    operations.add(ContentProviderOperation.newInsert(dataUri)
                                           .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, index)
                                           .withValue(ContactsContract.Data.MIMETYPE, CALL_MIMETYPE)
                                           .withValue(ContactsContract.Data.DATA1, e164number)
                                           .withValue(ContactsContract.Data.DATA2, context.getString(R.string.app_name))
                                           .withValue(ContactsContract.Data.DATA3, context.getString(R.string.ContactsDatabase_signal_call_s, e164number))
                                           .withYieldAllowed(true)
                                           .build());

    operations.add(ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                                           .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, aggregateId)
                                           .withValueBackReference(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, index)
                                           .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                                           .build());
  }

  private void removeTextSecureRawContact(List<ContentProviderOperation> operations,
                                          Account account, long rowId)
  {
    operations.add(ContentProviderOperation.newDelete(RawContacts.CONTENT_URI.buildUpon()
                                                                             .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                                                                             .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
                                                                             .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build())
                                           .withYieldAllowed(true)
                                           .withSelection(BaseColumns._ID + " = ?", new String[] {String.valueOf(rowId)})
                                           .build());
  }

  private @NonNull Map<String, SignalContact> getSignalRawContacts(@NonNull Account account) {
    Uri currentContactsUri = RawContacts.CONTENT_URI.buildUpon()
                                                    .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                                                    .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type).build();

    Map<String, SignalContact> signalContacts = new HashMap<>();
    Cursor                     cursor         = null;

    try {
      String[] projection = new String[] {BaseColumns._ID, RawContacts.SYNC1, RawContacts.SYNC4, RawContacts.CONTACT_ID, RawContacts.DISPLAY_NAME_PRIMARY, RawContacts.DISPLAY_NAME_SOURCE};

      cursor = context.getContentResolver().query(currentContactsUri, projection, null, null, null);

      while (cursor != null && cursor.moveToNext()) {
        String  currentAddress              = PhoneNumberFormatter.get(context).format(cursor.getString(1));
        long    rawContactId                = cursor.getLong(0);
        long    contactId                   = cursor.getLong(3);
        String  supportsVoice               = cursor.getString(2);
        String  rawContactDisplayName       = cursor.getString(4);
        String  aggregateDisplayName        = getDisplayName(contactId);
        int     rawContactDisplayNameSource = cursor.getInt(5);

        signalContacts.put(currentAddress, new SignalContact(rawContactId, supportsVoice, rawContactDisplayName, aggregateDisplayName, rawContactDisplayNameSource));
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }

    return signalContacts;
  }

  private Optional<SystemContactInfo> getSystemContactInfo(@NonNull String address)
  {
    Uri      uri          = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address));
    String[] projection   = {ContactsContract.PhoneLookup.NUMBER,
                             ContactsContract.PhoneLookup._ID,
                             ContactsContract.PhoneLookup.DISPLAY_NAME};
    Cursor   numberCursor = null;
    Cursor   idCursor     = null;

    try {
      numberCursor = context.getContentResolver().query(uri, projection, null, null, null);

      while (numberCursor != null && numberCursor.moveToNext()) {
        String systemNumber  = numberCursor.getString(0);
        String systemAddress = PhoneNumberFormatter.get(context).format(systemNumber);

        if (systemAddress.equals(address)) {
          idCursor = context.getContentResolver().query(RawContacts.CONTENT_URI,
                                                        new String[] {RawContacts._ID},
                                                        RawContacts.CONTACT_ID + " = ? ",
                                                        new String[] {String.valueOf(numberCursor.getLong(1))},
                                                        null);

          if (idCursor != null && idCursor.moveToNext()) {
            return Optional.of(new SystemContactInfo(numberCursor.getString(2),
                                                     numberCursor.getString(0),
                                                     idCursor.getLong(0)));
          }
        }
      }
    } finally {
      if (numberCursor != null) numberCursor.close();
      if (idCursor     != null) idCursor.close();
    }

    return Optional.absent();
  }

  private @Nullable String getDisplayName(long contactId) {
    Cursor cursor = context.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                                                       new String[]{ContactsContract.Contacts.DISPLAY_NAME},
                                                       ContactsContract.Contacts._ID + " = ?",
                                                       new String[] {String.valueOf(contactId)},
                                                       null);

    try {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getString(0);
      } else {
        return null;
      }
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  private void applyOperationsInBatches(@NonNull ContentResolver contentResolver,
                                        @NonNull String authority,
                                        @NonNull List<ContentProviderOperation> operations,
                                        int batchSize)
      throws OperationApplicationException, RemoteException
  {
    List<List<ContentProviderOperation>> batches = Util.chunk(operations, batchSize);
    for (List<ContentProviderOperation> batch : batches) {
      contentResolver.applyBatch(authority, new ArrayList<>(batch));
    }
  }

  private static class SystemContactInfo {
    private final String name;
    private final String number;
    private final long   id;

    private SystemContactInfo(String name, String number, long id) {
      this.name   = name;
      this.number = number;
      this.id     = id;
    }
  }

  private static class SignalContact {

              private final long   id;
    @Nullable private final String supportsVoice;
    @Nullable private final String rawDisplayName;
    @Nullable private final String aggregateDisplayName;
              private final int    displayNameSource;

    SignalContact(long id,
                  @Nullable String supportsVoice,
                  @Nullable String rawDisplayName,
                  @Nullable String aggregateDisplayName,
                  int displayNameSource)
    {
      this.id                   = id;
      this.supportsVoice        = supportsVoice;
      this.rawDisplayName       = rawDisplayName;
      this.aggregateDisplayName = aggregateDisplayName;
      this.displayNameSource    = displayNameSource;
    }

    public long getId() {
      return id;
    }

    boolean isVoiceSupported() {
      return "true".equals(supportsVoice);
    }

    @Nullable
    String getRawDisplayName() {
      return rawDisplayName;
    }

    @Nullable
    String getAggregateDisplayName() {
      return aggregateDisplayName;
    }

    int getDisplayNameSource() {
      return displayNameSource;
    }
  }
}
