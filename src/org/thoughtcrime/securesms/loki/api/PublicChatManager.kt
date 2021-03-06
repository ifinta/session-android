package org.thoughtcrime.securesms.loki.api

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.text.TextUtils
import androidx.annotation.WorkerThread
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.database.DatabaseContentProviders
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.groups.GroupManager
import org.thoughtcrime.securesms.util.BitmapUtil
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.Util
import org.whispersystems.signalservice.loki.api.opengroups.PublicChatInfo
import org.whispersystems.signalservice.loki.api.opengroups.PublicChat
import kotlin.jvm.Throws

class PublicChatManager(private val context: Context) {
  private var chats = mutableMapOf<Long, PublicChat>()
  private val pollers = mutableMapOf<Long, PublicChatPoller>()
  private val observers = mutableMapOf<Long, ContentObserver>()
  private var isPolling = false

  public fun areAllCaughtUp(): Boolean {
    var areAllCaughtUp = true
    refreshChatsAndPollers()
    for ((threadID, chat) in chats) {
      val poller = pollers[threadID]
      areAllCaughtUp = if (poller != null) areAllCaughtUp && poller.isCaughtUp else true
    }
    return areAllCaughtUp
  }

  public fun markAllAsNotCaughtUp() {
    refreshChatsAndPollers()
    for ((threadID, chat) in chats) {
      val poller = pollers[threadID] ?: PublicChatPoller(context, chat)
      poller.isCaughtUp = false
    }
  }

  public fun startPollersIfNeeded() {
    refreshChatsAndPollers()

    for ((threadId, chat) in chats) {
      val poller = pollers[threadId] ?: PublicChatPoller(context, chat)
      poller.startIfNeeded()
      listenToThreadDeletion(threadId)
      if (!pollers.containsKey(threadId)) { pollers[threadId] = poller }
    }
    isPolling = true
  }

  public fun stopPollers() {
    pollers.values.forEach { it.stop() }
    isPolling = false
  }

  //TODO Declare a specific type of checked exception instead of "Exception".
  @WorkerThread
  @Throws(java.lang.Exception::class)
  public fun addChat(server: String, channel: Long): PublicChat {
    val groupChatAPI = ApplicationContext.getInstance(context).publicChatAPI
            ?: throw IllegalStateException("LokiPublicChatAPI is not set!")

    // Ensure the auth token is acquired.
    groupChatAPI.getAuthToken(server).get()

    val channelInfo = groupChatAPI.getChannelInfo(channel, server).get()
    return addChat(server, channel, channelInfo)
  }

  @WorkerThread
  public fun addChat(server: String, channel: Long, info: PublicChatInfo): PublicChat {
    val chat = PublicChat(channel, server, info.displayName, true)
    var threadID = GroupManager.getOpenGroupThreadID(chat.id, context)
    var profilePicture: Bitmap? = null
    // Create the group if we don't have one
    if (threadID < 0) {
      if (info.profilePictureURL.isNotEmpty()) {
        val profilePictureAsByteArray = ApplicationContext.getInstance(context).publicChatAPI
                ?.downloadOpenGroupProfilePicture(server, info.profilePictureURL)
        profilePicture = BitmapUtil.fromByteArray(profilePictureAsByteArray)
      }
      val result = GroupManager.createOpenGroup(chat.id, context, profilePicture, chat.displayName)
      threadID = result.threadId
    }
    DatabaseFactory.getLokiThreadDatabase(context).setPublicChat(chat, threadID)
    // Set our name on the server
    val displayName = TextSecurePreferences.getProfileName(context)
    if (!TextUtils.isEmpty(displayName)) {
      ApplicationContext.getInstance(context).publicChatAPI?.setDisplayName(displayName, server)
    }
    // Start polling
    Util.runOnMain { startPollersIfNeeded() }

    return chat
  }

  public fun removeChat(server: String, channel: Long) {
    val threadDB = DatabaseFactory.getThreadDatabase(context)
    val groupId = PublicChat.getId(channel, server)
    val threadId = GroupManager.getOpenGroupThreadID(groupId, context)
    val groupAddress = threadDB.getRecipientForThreadId(threadId)!!.address.serialize()
    GroupManager.deleteGroup(groupAddress, context)

    Util.runOnMain { startPollersIfNeeded() }
  }

  private fun refreshChatsAndPollers() {
    val chatsInDB = DatabaseFactory.getLokiThreadDatabase(context).getAllPublicChats()
    val removedChatThreadIds = chats.keys.filter { !chatsInDB.keys.contains(it) }
    removedChatThreadIds.forEach { pollers.remove(it)?.stop() }

    // Only append to chats if we have a thread for the chat
    chats = chatsInDB.filter { GroupManager.getOpenGroupThreadID(it.value.id, context) > -1 }.toMutableMap()
  }

  private fun listenToThreadDeletion(threadID: Long) {
    if (threadID < 0 || observers[threadID] != null) { return }
    val observer = createDeletionObserver(threadID) {
      val chat = chats[threadID]

      // Reset last message cache
      if (chat != null) {
        val apiDatabase = DatabaseFactory.getLokiAPIDatabase(context)
        apiDatabase.removeLastDeletionServerID(chat.channel, chat.server)
        apiDatabase.removeLastMessageServerID(chat.channel, chat.server)
      }

      DatabaseFactory.getLokiThreadDatabase(context).removePublicChat(threadID)
      pollers.remove(threadID)?.stop()
      observers.remove(threadID)
      startPollersIfNeeded()
    }
    observers[threadID] = observer

    context.applicationContext.contentResolver.registerContentObserver(DatabaseContentProviders.Conversation.getUriForThread(threadID), true, observer)
  }

  private fun createDeletionObserver(threadID: Long, onDelete: Runnable): ContentObserver {
    return object : ContentObserver(null) {

      override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        // Stop the poller if thread is deleted
        try {
          if (!DatabaseFactory.getThreadDatabase(context).hasThread(threadID)) {
            onDelete.run()
            context.applicationContext.contentResolver.unregisterContentObserver(this)
          }
        } catch (e: Exception) {
          // TODO: Handle
        }
      }
    }
  }
}