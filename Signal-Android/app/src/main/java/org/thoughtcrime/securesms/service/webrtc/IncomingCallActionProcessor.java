package org.thoughtcrime.securesms.service.webrtc;

import android.net.Uri;
import android.os.ResultReceiver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.signal.ringrtc.CallException;
import org.signal.ringrtc.CallId;
import org.signal.ringrtc.CallManager;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.events.CallParticipant;
import org.thoughtcrime.securesms.events.WebRtcViewModel;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.notifications.DoNotDisturbUtil;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.ringrtc.CallState;
import org.thoughtcrime.securesms.ringrtc.RemotePeer;
import org.thoughtcrime.securesms.service.webrtc.state.VideoState;
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceState;
import org.thoughtcrime.securesms.util.NetworkUtil;
import org.thoughtcrime.securesms.webrtc.locks.LockManager;
import org.webrtc.PeerConnection;

import java.util.List;
import java.util.Objects;

import static org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.TYPE_INCOMING_RINGING;

/**
 * Responsible for setting up and managing the start of an incoming 1:1 call. Transitioned
 * to from idle or pre-join and can either move to a connected state (user picks up) or
 * a disconnected state (remote hangup, local hangup, etc.).
 */
public class IncomingCallActionProcessor extends DeviceAwareActionProcessor {

  private static final String TAG = Log.tag(IncomingCallActionProcessor.class);

  private final ActiveCallActionProcessorDelegate activeCallDelegate;
  private final CallSetupActionProcessorDelegate  callSetupDelegate;

  public IncomingCallActionProcessor(@NonNull WebRtcInteractor webRtcInteractor) {
    super(webRtcInteractor, TAG);
    activeCallDelegate = new ActiveCallActionProcessorDelegate(webRtcInteractor, TAG);
    callSetupDelegate  = new CallSetupActionProcessorDelegate(webRtcInteractor, TAG);
  }

  @Override
  protected @NonNull WebRtcServiceState handleIsInCallQuery(@NonNull WebRtcServiceState currentState, @Nullable ResultReceiver resultReceiver) {
    return activeCallDelegate.handleIsInCallQuery(currentState, resultReceiver);
  }

  @Override
  public @NonNull WebRtcServiceState handleTurnServerUpdate(@NonNull WebRtcServiceState currentState,
                                                            @NonNull List<PeerConnection.IceServer> iceServers,
                                                            boolean isAlwaysTurn)
  {
    RemotePeer      activePeer      = currentState.getCallInfoState().requireActivePeer();
    boolean         hideIp          = !activePeer.getRecipient().isSystemContact() || isAlwaysTurn;
    VideoState      videoState      = currentState.getVideoState();
    CallParticipant callParticipant = Objects.requireNonNull(currentState.getCallInfoState().getRemoteCallParticipant(activePeer.getRecipient()));

    try {
      webRtcInteractor.getCallManager().proceed(activePeer.getCallId(),
                                                context,
                                                videoState.getLockableEglBase().require(),
                                                SignalStore.internalValues().audioProcessingMethod(),
                                                videoState.requireLocalSink(),
                                                callParticipant.getVideoSink(),
                                                videoState.requireCamera(),
                                                iceServers,
                                                hideIp,
                                                NetworkUtil.getCallingBandwidthMode(context),
                                                false);
    } catch (CallException e) {
      return callFailure(currentState, "Unable to proceed with call: ", e);
    }

    webRtcInteractor.updatePhoneState(LockManager.PhoneState.PROCESSING);
    webRtcInteractor.postStateUpdate(currentState);

    return currentState;
  }

  @Override
  protected @NonNull WebRtcServiceState handleAcceptCall(@NonNull WebRtcServiceState currentState, boolean answerWithVideo) {
    RemotePeer activePeer = currentState.getCallInfoState().requireActivePeer();

    Log.i(TAG, "handleAcceptCall(): call_id: " + activePeer.getCallId());

    SignalDatabase.sms().insertReceivedCall(activePeer.getId(), currentState.getCallSetupState(activePeer).isRemoteVideoOffer());

    currentState = currentState.builder()
                               .changeCallSetupState(activePeer.getCallId())
                               .acceptWithVideo(answerWithVideo)
                               .build();

    try {
      webRtcInteractor.getCallManager().acceptCall(activePeer.getCallId());
    } catch (CallException e) {
      return callFailure(currentState, "accept() failed: ", e);
    }

    return currentState;
  }

  protected @NonNull WebRtcServiceState handleDenyCall(@NonNull WebRtcServiceState currentState) {
    RemotePeer activePeer = currentState.getCallInfoState().requireActivePeer();

    if (activePeer.getState() != CallState.LOCAL_RINGING) {
      Log.w(TAG, "Can only deny from ringing!");
      return currentState;
    }

    Log.i(TAG, "handleDenyCall():");

    try {
      webRtcInteractor.getCallManager().hangup();
      SignalDatabase.sms().insertMissedCall(activePeer.getId(), System.currentTimeMillis(), currentState.getCallSetupState(activePeer).isRemoteVideoOffer());
      return terminate(currentState, activePeer);
    } catch  (CallException e) {
      return callFailure(currentState, "hangup() failed: ", e);
    }
  }

  protected @NonNull WebRtcServiceState handleLocalRinging(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    Log.i(TAG, "handleLocalRinging(): call_id: " + remotePeer.getCallId());

    RemotePeer activePeer = currentState.getCallInfoState().requireActivePeer();
    Recipient  recipient  = remotePeer.getRecipient();

    activePeer.localRinging();
    webRtcInteractor.updatePhoneState(LockManager.PhoneState.INTERACTIVE);

    boolean shouldDisturbUserWithCall = DoNotDisturbUtil.shouldDisturbUserWithCall(context.getApplicationContext(), recipient);
    if (shouldDisturbUserWithCall) {
      boolean started = webRtcInteractor.startWebRtcCallActivityIfPossible();
      if (!started) {
        Log.i(TAG, "Unable to start call activity due to OS version or not being in the foreground");
        ApplicationDependencies.getAppForegroundObserver().addListener(webRtcInteractor.getForegroundListener());
      }
    }

    webRtcInteractor.initializeAudioForCall();
    if (shouldDisturbUserWithCall && SignalStore.settings().isCallNotificationsEnabled()) {
      Uri                            ringtone     = recipient.resolve().getCallRingtone();
      RecipientDatabase.VibrateState vibrateState = recipient.resolve().getCallVibrate();

      if (ringtone == null) {
        ringtone = SignalStore.settings().getCallRingtone();
      }

      webRtcInteractor.startIncomingRinger(ringtone, vibrateState == RecipientDatabase.VibrateState.ENABLED || (vibrateState == RecipientDatabase.VibrateState.DEFAULT && SignalStore.settings().isCallVibrateEnabled()));
    }

    webRtcInteractor.setCallInProgressNotification(TYPE_INCOMING_RINGING, activePeer);
    webRtcInteractor.registerPowerButtonReceiver();

    return currentState.builder()
                       .changeCallInfoState()
                       .callState(WebRtcViewModel.State.CALL_INCOMING)
                       .build();
  }

  protected @NonNull WebRtcServiceState handleScreenOffChange(@NonNull WebRtcServiceState currentState) {
    Log.i(TAG, "Silencing incoming ringer...");

    webRtcInteractor.silenceIncomingRinger();
    return currentState;
  }

  @Override
  protected @NonNull  WebRtcServiceState handleRemoteVideoEnable(@NonNull WebRtcServiceState currentState, boolean enable) {
    return activeCallDelegate.handleRemoteVideoEnable(currentState, enable);
  }

  @Override
  protected @NonNull WebRtcServiceState handleScreenSharingEnable(@NonNull WebRtcServiceState currentState, boolean enable) {
    return activeCallDelegate.handleScreenSharingEnable(currentState, enable);
  }

  @Override
  protected @NonNull WebRtcServiceState handleReceivedOfferWhileActive(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    return activeCallDelegate.handleReceivedOfferWhileActive(currentState, remotePeer);
  }

  @Override
  protected @NonNull WebRtcServiceState handleEndedRemote(@NonNull WebRtcServiceState currentState, @NonNull CallManager.CallEvent endedRemoteEvent, @NonNull RemotePeer remotePeer) {
    return activeCallDelegate.handleEndedRemote(currentState, endedRemoteEvent, remotePeer);
  }

  @Override
  protected @NonNull WebRtcServiceState handleEnded(@NonNull WebRtcServiceState currentState, @NonNull CallManager.CallEvent endedEvent, @NonNull RemotePeer remotePeer) {
    return activeCallDelegate.handleEnded(currentState, endedEvent, remotePeer);
  }

  @Override
  protected  @NonNull WebRtcServiceState handleSetupFailure(@NonNull WebRtcServiceState currentState, @NonNull CallId callId) {
    return activeCallDelegate.handleSetupFailure(currentState, callId);
  }

  @Override
  public @NonNull WebRtcServiceState handleCallConnected(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    return callSetupDelegate.handleCallConnected(currentState, remotePeer);
  }

  @Override
  protected @NonNull WebRtcServiceState handleSetEnableVideo(@NonNull WebRtcServiceState currentState, boolean enable) {
    return callSetupDelegate.handleSetEnableVideo(currentState, enable);
  }
}
