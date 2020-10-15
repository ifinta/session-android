package org.thoughtcrime.securesms.components.voice;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerNotificationManager;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.color.MaterialColor;
import org.thoughtcrime.securesms.contacts.avatars.ContactColors;
import org.thoughtcrime.securesms.conversation.ConversationActivity;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.recipients.RecipientId;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Android Service responsible for playback of voice notes.
 */
public class VoiceNotePlaybackService extends MediaBrowserServiceCompat {

  private static final String TAG             = Log.tag(VoiceNotePlaybackService.class);
  private static final String EMPTY_ROOT_ID   = "empty-root-id";

  private static final long SUPPORTED_ACTIONS = PlaybackStateCompat.ACTION_PLAY    |
                                                PlaybackStateCompat.ACTION_PAUSE   |
                                                PlaybackStateCompat.ACTION_SEEK_TO |
                                                PlaybackStateCompat.ACTION_STOP    |
                                                PlaybackStateCompat.ACTION_PLAY_PAUSE;

  private MediaSessionCompat           mediaSession;
  private MediaSessionConnector        mediaSessionConnector;
  private PlaybackStateCompat.Builder  stateBuilder;
  private SimpleExoPlayer              player;
  private BecomingNoisyReceiver        becomingNoisyReceiver;
  private VoiceNoteNotificationManager voiceNoteNotificationManager;
  private VoiceNoteQueueDataAdapter    queueDataAdapter;
  private boolean                      isForegroundService;

  private final LoadControl loadControl = new DefaultLoadControl.Builder()
                                                                .setBufferDurationsMs(Integer.MAX_VALUE,
                                                                                      Integer.MAX_VALUE,
                                                                                      Integer.MAX_VALUE,
                                                                                      Integer.MAX_VALUE)
                                                                .createDefaultLoadControl();

  @Override
  public void onCreate() {
    super.onCreate();

    mediaSession                 = new MediaSessionCompat(this, TAG);
    stateBuilder                 = new PlaybackStateCompat.Builder()
                                                          .setActions(SUPPORTED_ACTIONS);
    mediaSessionConnector        = new MediaSessionConnector(mediaSession, null);
    becomingNoisyReceiver        = new BecomingNoisyReceiver(this, mediaSession.getSessionToken());
    player                       = ExoPlayerFactory.newSimpleInstance(this, new DefaultRenderersFactory(this), new DefaultTrackSelector(), loadControl);
    queueDataAdapter             = new VoiceNoteQueueDataAdapter();
    voiceNoteNotificationManager = new VoiceNoteNotificationManager(this,
                                                                    mediaSession.getSessionToken(),
                                                                    new VoiceNoteNotificationManagerListener());

    VoiceNoteMediaSourceFactory mediaSourceFactory = new VoiceNoteMediaSourceFactory(this);

    mediaSession.setPlaybackState(stateBuilder.build());

    player.addListener(new VoiceNotePlayerEventListener());
    player.setAudioAttributes(new AudioAttributes.Builder()
                                                 .setContentType(C.CONTENT_TYPE_SPEECH)
                                                 .setUsage(C.USAGE_MEDIA)
                                                 .build());

    mediaSessionConnector.setPlayer(player, new VoiceNotePlaybackPreparer(this, player, queueDataAdapter, mediaSourceFactory));
    mediaSessionConnector.setQueueNavigator(new VoiceNoteQueueNavigator(mediaSession, queueDataAdapter));

    setSessionToken(mediaSession.getSessionToken());

    mediaSession.setActive(true);
  }

  @Override
  public void onTaskRemoved(Intent rootIntent) {
    super.onTaskRemoved(rootIntent);

    player.stop(true);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    mediaSession.setActive(false);
    mediaSession.release();
    becomingNoisyReceiver.unregister();
    player.release();
  }

  @Override
  public @Nullable BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
    if (clientUid == Process.myUid()) {
      return new BrowserRoot(EMPTY_ROOT_ID, null);
    } else {
      return null;
    }
  }

  @Override
  public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
    result.sendResult(Collections.emptyList());
  }

  private class VoiceNotePlayerEventListener implements Player.EventListener {
    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
      switch (playbackState) {
        case Player.STATE_BUFFERING:
        case Player.STATE_READY:
          voiceNoteNotificationManager.showNotification(player);

          if (!playWhenReady) {
            stopForeground(false);
            becomingNoisyReceiver.unregister();
          } else {
            becomingNoisyReceiver.register();
          }
          break;
        default:
          becomingNoisyReceiver.unregister();
          voiceNoteNotificationManager.hideNotification();
      }
    }
  }

  private class VoiceNoteNotificationManagerListener implements PlayerNotificationManager.NotificationListener {

    @Override
    public void onNotificationStarted(int notificationId, Notification notification) {
      if (!isForegroundService) {
        ContextCompat.startForegroundService(getApplicationContext(), new Intent(getApplicationContext(), VoiceNotePlaybackService.class));
        startForeground(notificationId, notification);
        isForegroundService = true;
      }
    }

    @Override
    public void onNotificationCancelled(int notificationId) {
      stopForeground(true);
      isForegroundService = false;
      stopSelf();
    }
  }

  /**
   * Receiver to pause playback when things become noisy.
   */
  private static class BecomingNoisyReceiver extends BroadcastReceiver {
    private static final IntentFilter NOISY_INTENT_FILTER = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

    private final Context               context;
    private final MediaControllerCompat controller;

    private boolean registered;

    private BecomingNoisyReceiver(Context context, MediaSessionCompat.Token token) {
      this.context = context;
      try {
        this.controller = new MediaControllerCompat(context, token);
      } catch (RemoteException e) {
        throw new IllegalArgumentException("Failed to create controller from token", e);
      }
    }

    void register() {
      if (!registered) {
        context.registerReceiver(this, NOISY_INTENT_FILTER);
        registered = true;
      }
    }

    void unregister() {
      if (registered) {
        context.unregisterReceiver(this);
        registered = false;
      }
    }

    public void onReceive(Context context, @NonNull Intent intent) {
      if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
        controller.getTransportControls().pause();
      }
    }
  }
}