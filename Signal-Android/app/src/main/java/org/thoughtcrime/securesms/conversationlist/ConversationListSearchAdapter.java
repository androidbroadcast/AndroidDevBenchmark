package org.thoughtcrime.securesms.conversationlist;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.conversationlist.model.ConversationSet;
import org.thoughtcrime.securesms.search.MessageResult;
import org.thoughtcrime.securesms.search.SearchResult;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.StickyHeaderDecoration;

import java.util.Collections;
import java.util.Locale;

class ConversationListSearchAdapter extends    RecyclerView.Adapter<ConversationListSearchAdapter.SearchResultViewHolder>
                        implements StickyHeaderDecoration.StickyHeaderAdapter<ConversationListSearchAdapter.HeaderViewHolder>
{
  private static final int TYPE_CONVERSATIONS = 1;
  private static final int TYPE_CONTACTS      = 2;
  private static final int TYPE_MESSAGES      = 3;

  private final GlideRequests glideRequests;
  private final EventListener eventListener;
  private final Locale        locale;

  @NonNull
  private SearchResult searchResult = SearchResult.EMPTY;

  ConversationListSearchAdapter(@NonNull GlideRequests glideRequests,
                                @NonNull EventListener eventListener,
                                @NonNull Locale        locale)
  {
    this.glideRequests = glideRequests;
    this.eventListener = eventListener;
    this.locale        = locale;
  }

  @Override
  public @NonNull SearchResultViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new SearchResultViewHolder(LayoutInflater.from(parent.getContext())
                                                    .inflate(R.layout.conversation_list_item_view, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull SearchResultViewHolder holder, int position) {
    ThreadRecord conversationResult = getConversationResult(position);

    if (conversationResult != null) {
      holder.bind(conversationResult, glideRequests, eventListener, locale, searchResult.getQuery());
      return;
    }

    Recipient contactResult = getContactResult(position);

    if (contactResult != null) {
      holder.bind(contactResult, glideRequests, eventListener, locale, searchResult.getQuery());
      return;
    }

    MessageResult messageResult = getMessageResult(position);

    if (messageResult != null) {
      holder.bind(messageResult, glideRequests, eventListener, locale, searchResult.getQuery());
    }
  }

  @Override
  public void onViewRecycled(@NonNull SearchResultViewHolder holder) {
    holder.recycle();
  }

  @Override
  public int getItemCount() {
    return searchResult.size();
  }

  @Override
  public long getHeaderId(int position) {
    if (getConversationResult(position) != null) {
      return TYPE_CONVERSATIONS;
    } else if (getContactResult(position) != null) {
      return TYPE_CONTACTS;
    } else {
      return TYPE_MESSAGES;
    }
  }

  @Override
  public HeaderViewHolder onCreateHeaderViewHolder(ViewGroup parent, int position, int type) {
    return new HeaderViewHolder(LayoutInflater.from(parent.getContext())
                                              .inflate(R.layout.dsl_section_header, parent, false));
  }

  @Override
  public void onBindHeaderViewHolder(HeaderViewHolder viewHolder, int position, int type) {
    viewHolder.bind((int) getHeaderId(position));
  }

  void updateResults(@NonNull SearchResult result) {
    this.searchResult = result;
    notifyDataSetChanged();
  }

  @Nullable
  private ThreadRecord getConversationResult(int position) {
    if (position < searchResult.getConversations().size()) {
      return searchResult.getConversations().get(position);
    }
    return null;
  }

  @Nullable
  private Recipient getContactResult(int position) {
    if (position >= getFirstContactIndex() && position < getFirstMessageIndex()) {
      return searchResult.getContacts().get(position - getFirstContactIndex());
    }
    return null;
  }

  @Nullable
  private MessageResult getMessageResult(int position) {
    if (position >= getFirstMessageIndex() && position < searchResult.size()) {
      return searchResult.getMessages().get(position - getFirstMessageIndex());
    }
    return null;
  }

  private int getFirstContactIndex() {
    return searchResult.getConversations().size();
  }

  private int getFirstMessageIndex() {
    return getFirstContactIndex() + searchResult.getContacts().size();
  }

  public interface EventListener {
    void onConversationClicked(@NonNull ThreadRecord threadRecord);
    void onContactClicked(@NonNull Recipient contact);
    void onMessageClicked(@NonNull MessageResult message);
  }

  static class SearchResultViewHolder extends RecyclerView.ViewHolder {

    private final ConversationListItem root;

    SearchResultViewHolder(View itemView) {
      super(itemView);
      root = (ConversationListItem) itemView;
    }

    void bind(@NonNull  ThreadRecord  conversationResult,
              @NonNull  GlideRequests glideRequests,
              @NonNull  EventListener eventListener,
              @NonNull  Locale        locale,
              @Nullable String        query)
    {
      root.bindThread(conversationResult, glideRequests, locale, Collections.emptySet(), new ConversationSet(), query);
      root.setOnClickListener(view -> eventListener.onConversationClicked(conversationResult));
    }

    void bind(@NonNull  Recipient     contactResult,
              @NonNull  GlideRequests glideRequests,
              @NonNull  EventListener eventListener,
              @NonNull  Locale        locale,
              @Nullable String        query)
    {
      root.bindContact(contactResult, glideRequests, locale, query);
      root.setOnClickListener(view -> eventListener.onContactClicked(contactResult));
    }

    void bind(@NonNull  MessageResult messageResult,
              @NonNull  GlideRequests glideRequests,
              @NonNull  EventListener eventListener,
              @NonNull  Locale        locale,
              @Nullable String        query)
    {
      root.bindMessage(messageResult, glideRequests, locale, query);
      root.setOnClickListener(view -> eventListener.onMessageClicked(messageResult));
    }

    void recycle() {
      root.unbind();
      root.setOnClickListener(null);
    }
  }

  public static class HeaderViewHolder extends RecyclerView.ViewHolder {

    private TextView titleView;

    public HeaderViewHolder(View itemView) {
      super(itemView);
      titleView = itemView.findViewById(R.id.section_header);
    }

    public void bind(int headerType) {
      switch (headerType) {
        case TYPE_CONVERSATIONS:
          titleView.setText(R.string.SearchFragment_header_conversations);
          break;
        case TYPE_CONTACTS:
          titleView.setText(R.string.SearchFragment_header_contacts);
          break;
        case TYPE_MESSAGES:
          titleView.setText(R.string.SearchFragment_header_messages);
          break;
      }
    }
  }
}
