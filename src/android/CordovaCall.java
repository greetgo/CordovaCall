package com.dmarc.cordovacall;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import android.os.Bundle;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.Manifest;
import android.telecom.Connection;
import org.json.JSONArray;
import org.json.JSONException;

import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.List;

import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.util.Log;
import com.android.internal.telephony.ITelephony;

public class CordovaCall extends CordovaPlugin {

    private static String TAG = "com.dmarc.cordovacall";
    public static final int CALL_PHONE_REQ_CODE = 0;
    public static final int REAL_PHONE_CALL = 1;
    private int permissionCounter = 0;
    private TelecomManager tm;
    private PhoneAccountHandle handle;
    private PhoneAccount phoneAccount;
    private CallbackContext callbackContext;
    private String appName;
    private String from;
    private String to;
    private String realCallTo;
    private static HashMap<String, ArrayList<CallbackContext>> callbackContextMap = new HashMap<String, ArrayList<CallbackContext>>();
    private static CordovaInterface cordovaInterface;
    private static Icon icon;

    private ITelephony telephonyService;

    //The receiver will be recreated whenever android feels like it.  We need a static variable to remember data between instantiations
    static PhonecallStartEndDetector listener;

    public static HashMap<String, ArrayList<CallbackContext>> getCallbackContexts() {
        return callbackContextMap;
    }

    public static CordovaInterface getCordova() {
        return cordovaInterface;
    }

    public static Icon getIcon() {
        return icon;
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        cordovaInterface = cordova;
        super.initialize(cordova, webView);
        appName = getApplicationName(this.cordova.getActivity().getApplicationContext());
        handle = new PhoneAccountHandle(new ComponentName(this.cordova.getActivity().getApplicationContext(), MyConnectionService.class), appName);
        tm = (TelecomManager) this.cordova.getActivity().getApplicationContext().getSystemService(this.cordova.getActivity().getApplicationContext().TELECOM_SERVICE);
        phoneAccount = new PhoneAccount.Builder(handle, appName)
          .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
          .build();

        Log.e(TAG, "initialize");

        if (listener == null) {
            listener = new PhonecallStartEndDetector();
        }

        //The other intent tells us the phone state changed.  Here we set a listener to deal with it
        TelephonyManager telephony = (TelephonyManager) this.cordova.getActivity().getApplicationContext().getSystemService(this.cordova.getActivity().getApplicationContext().TELEPHONY_SERVICE);
        telephony.listen(listener,
          PhoneStateListener.LISTEN_CALL_STATE
        );

        try {
            Class<?> c = Class.forName(telephony.getClass().getName());
            Method m = c.getDeclaredMethod("getITelephony");
            m.setAccessible(true);
            Log.e(TAG, "getITelephony.asd");
            telephonyService = (ITelephony) m.invoke(telephony);
            Log.e(TAG, "getITelephony.asd.telephonyService " + telephonyService);
        } catch (Exception ignore) {
            Log.e(TAG, ignore.getMessage());
        }

        callbackContextMap.put("answer", new ArrayList<CallbackContext>());
        callbackContextMap.put("reject", new ArrayList<CallbackContext>());
        callbackContextMap.put("hangup", new ArrayList<CallbackContext>());
        callbackContextMap.put("sendCall", new ArrayList<CallbackContext>());
        callbackContextMap.put("receiveCall", new ArrayList<CallbackContext>());
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        this.receiveCall();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;
        if (action.equals("receiveCall")) {
            Connection conn = MyConnectionService.getConnection();
            if (conn != null) {
                if (conn.getState() == Connection.STATE_ACTIVE) {
                    this.callbackContext.error("You can't receive a call right now because you're already in a call");
                } else {
                    this.callbackContext.error("You can't receive a call right now");
                }
            } else {
                from = args.getString(0);
                permissionCounter = 2;
                this.receiveCall();
            }
            return true;
        } else if (action.equals("sendCall")) {
            Connection conn = MyConnectionService.getConnection();
            if (conn != null) {
                if (conn.getState() == Connection.STATE_ACTIVE) {
                    this.callbackContext.error("You can't make a call right now because you're already in a call");
                } else if (conn.getState() == Connection.STATE_DIALING) {
                    this.callbackContext.error("You can't make a call right now because you're already trying to make a call");
                } else {
                    this.callbackContext.error("You can't make a call right now");
                }
            } else {
                to = args.getString(0);
                listener.setOutgoingNumber(to);
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        getCallPhonePermission();
                    }
                });
            }
            return true;
        } else if (action.equals("connectCall")) {
            Connection conn = MyConnectionService.getConnection();
            if (conn == null) {
                this.callbackContext.error("No call exists for you to connect");
            } else if (conn.getState() == Connection.STATE_ACTIVE) {
                this.callbackContext.error("Your call is already connected");
            } else {
                conn.setActive();
                Intent intent = new Intent(this.cordova.getActivity().getApplicationContext(), this.cordova.getActivity().getClass());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                this.cordova.getActivity().getApplicationContext().startActivity(intent);
                this.callbackContext.success("Call connected successfully");
            }
            return true;
        } else if (action.equals("endCall")) {
      /*Connection conn = MyConnectionService.getConnection();
      if (conn == null) {
        this.callbackContext.error("No call exists for you to end");
      } else {
        DisconnectCause cause = new DisconnectCause(DisconnectCause.LOCAL);
        conn.setDisconnected(cause);
        conn.destroy();
        MyConnectionService.deinitConnection();
        ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("hangup");
        for (final CallbackContext cbContext : callbackContexts) {
          cordova.getThreadPool().execute(new Runnable() {
            public void run() {
              PluginResult result = new PluginResult(PluginResult.Status.OK, "hangup event called successfully");
              result.setKeepCallback(true);
              cbContext.sendPluginResult(result);
            }
          });
        }
        this.callbackContext.success("Call ended successfully");
      }*/

      /*ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("hangup");
      if (callbackContexts != null) {
        for (final CallbackContext cbContext : callbackContexts) {
          cordova.getThreadPool().execute(new Runnable() {
            public void run() {
              PluginResult result = new PluginResult(PluginResult.Status.OK, "hangup event called successfully");
              result.setKeepCallback(true);
              cbContext.sendPluginResult(result);
            }
          });
        }
      }*/

            this.callbackContext.success("Call ended successfully");
            onDestroy();

            return true;
        } else if (action.equals("registerEvent")) {
            String eventType = args.getString(0);
            ArrayList<CallbackContext> callbackContextList = callbackContextMap.get(eventType);
            callbackContextList.add(this.callbackContext);
            return true;
        } else if (action.equals("setAppName")) {
            String appName = args.getString(0);
            handle = new PhoneAccountHandle(new ComponentName(this.cordova.getActivity().getApplicationContext(), MyConnectionService.class), appName);
            phoneAccount = new PhoneAccount.Builder(handle, appName)
              .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
              .build();
            this.callbackContext.success("App Name Changed Successfully");
            return true;
        } else if (action.equals("setIcon")) {
            String iconName = args.getString(0);
            int iconId = this.cordova.getActivity().getApplicationContext().getResources().getIdentifier(iconName, "drawable", this.cordova.getActivity().getPackageName());
            if (iconId != 0) {
                icon = Icon.createWithResource(this.cordova.getActivity(), iconId);
                this.callbackContext.success("Icon Changed Successfully");
            } else {
                this.callbackContext.error("This icon does not exist. Make sure to add it to the res/drawable folder the right way.");
            }
            return true;
        } else if (action.equals("mute")) {
            this.mute();
            this.callbackContext.success("Muted Successfully");
            return true;
        } else if (action.equals("unmute")) {
            this.unmute();
            this.callbackContext.success("Unmuted Successfully");
            return true;
        } else if (action.equals("speakerOn")) {
            this.speakerOn();
            this.callbackContext.success("Speakerphone is on");
            return true;
        } else if (action.equals("speakerOff")) {
            this.speakerOff();
            this.callbackContext.success("Speakerphone is off");
            return true;
        } else if (action.equals("sendRealCall")) {
            realCallTo = args.getString(0);
            if (realCallTo != null) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        sendRealCallPhonePermission();
                    }
                });
                this.callbackContext.success("Call Successful");
            } else {
                this.callbackContext.error("Call Failed. You need to enter a phone number.");
            }
            return true;
        }
        return false;
    }

    private void receiveCall() {
        if (permissionCounter >= 1) {
            try {
                Bundle callInfo = new Bundle();
                callInfo.putString("from", from);
                tm.addNewIncomingCall(handle, callInfo);
                permissionCounter = 0;
                this.callbackContext.success("Incoming call successful");
            } catch (Exception e) {
                if (permissionCounter == 2) {
                    tm.registerPhoneAccount(phoneAccount);
                    Intent phoneIntent = new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS);
                    phoneIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    this.cordova.getActivity().getApplicationContext().startActivity(phoneIntent);
                } else {
                    this.callbackContext.error("You need to accept phone account permissions in order to receive calls");
                }
            }
        }
        permissionCounter--;
    }

    private void sendCall() {
        Uri uri = Uri.fromParts("tel", to, null);
        Bundle callInfoBundle = new Bundle();
        callInfoBundle.putString("to", to);
        Bundle callInfo = new Bundle();
        callInfo.putParcelable(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callInfoBundle);
        callInfo.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle);
        callInfo.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, true);
        tm.placeCall(uri, callInfo);
        this.callbackContext.success("Outgoing call successful");
    }

    private void mute() {
        AudioManager audioManager = (AudioManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMicrophoneMute(true);
    }

    private void unmute() {
        AudioManager audioManager = (AudioManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMicrophoneMute(false);
    }

    private void speakerOn() {
        AudioManager audioManager = (AudioManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(true);
    }

    private void speakerOff() {
        AudioManager audioManager = (AudioManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(false);
    }

    public static String getApplicationName(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        int stringId = applicationInfo.labelRes;
        return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : context.getString(stringId);
    }

    protected void getCallPhonePermission() {
        cordova.requestPermission(this, CALL_PHONE_REQ_CODE, Manifest.permission.CALL_PHONE);
    }

    protected void sendRealCallPhonePermission() {
        cordova.requestPermission(this, REAL_PHONE_CALL, Manifest.permission.CALL_PHONE);
    }

    private void sendRealCall() {
        try {
            Intent intent = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", realCallTo, null));
            this.cordova.getActivity().getApplicationContext().startActivity(intent);
        } catch (Exception e) {
            this.callbackContext.error("Call Failed");
        }
        this.callbackContext.success("Call Successful");
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "CALL_PHONE Permission Denied"));
                return;
            }
        }
        switch (requestCode) {
            case CALL_PHONE_REQ_CODE:
                this.sendCall();
                break;
            case REAL_PHONE_CALL:
                this.sendRealCall();
                break;
        }
    }

    @Override
    public void onDestroy() {
        try {
            onDestroy0();
        } catch (Exception ignore) {
            Log.e(TAG, ignore.getMessage());
        }
    }

    private void onDestroy0() throws Exception {
        String str = listener.getSavedNumber();
        if (str == null || str.trim().length() == 0) return;
        telephonyService.endCall();
    }

    //Derived classes should override these to respond to specific events of interest
    protected void onIncomingCallStarted(String number, Date start) {
        Log.e(TAG, "onIncomingCallStarted number: " + number + " start: " + start);
    }

    protected void onOutgoingCallStarted(String number, Date start) {
        Log.e(TAG, "onOutgoingCallStarted number: " + number + " start: " + start);

        ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("sendCall");
        if (callbackContexts != null) {
            for (final CallbackContext callbackContext : callbackContexts) {
                CordovaCall.getCordova().getThreadPool().execute(new Runnable() {
                    public void run() {
                        PluginResult result = new PluginResult(PluginResult.Status.OK, "sendCall event called successfully 213");
                        result.setKeepCallback(true);
                        callbackContext.sendPluginResult(result);
                    }
                });
            }
        }

    }

    protected void onIncomingCallEnded(String number, Date start, Date end) {
        Log.e(TAG, "onIncomingCallEnded number: " + number + " start: " + start);
    }

    protected void onOutgoingCallEnded(String number, Date start, Date end) {
        Log.e(TAG, "onOutgoingCallEnded number: " + number + " start: " + start);

        ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("hangup");
        if (callbackContexts != null) {
            for (final CallbackContext callbackContext : callbackContexts) {
                CordovaCall.getCordova().getThreadPool().execute(new Runnable() {
                    public void run() {
                        PluginResult result = new PluginResult(PluginResult.Status.OK, "endCall event called successfully 213");
                        result.setKeepCallback(true);
                        callbackContext.sendPluginResult(result);
                    }
                });
            }
        }
    }

    protected void onMissedCall(String number, Date start) {
        Log.e(TAG, "onMissedCall number: " + number + " start: " + start);
    }

    //Deals with actual events
    public class PhonecallStartEndDetector extends PhoneStateListener {
        int lastState = TelephonyManager.CALL_STATE_IDLE;
        Date callStartTime;
        boolean isIncoming;
        String savedNumber;  //because the passed incoming is only valid in ringing

        public PhonecallStartEndDetector() {
        }

        //The outgoing number is only sent via a separate intent, so we need to store it out of band
        public void setOutgoingNumber(String number) {
            savedNumber = number;
        }

        public String getSavedNumber() {
            return savedNumber;
        }

        //Incoming call-  goes from IDLE to RINGING when it rings, to OFFHOOK when it's answered, to IDLE when its hung up
        //Outgoing call-  goes from IDLE to OFFHOOK when it dials out, to IDLE when hung up
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            super.onCallStateChanged(state, incomingNumber);
            Log.e(TAG, "onCallStateChanged state: " + state + " incomingNumber: " + incomingNumber);

            if (lastState == state) {
                //No change, debounce extras
                return;
            }

            if (savedNumber == null) {
                lastState = state;
                return;
            }

            switch (state) {
                case TelephonyManager.CALL_STATE_RINGING:
                    isIncoming = true;
                    callStartTime = new Date();
                    savedNumber = incomingNumber;
                    onIncomingCallStarted(incomingNumber, callStartTime);
                    break;
                case TelephonyManager.CALL_STATE_OFFHOOK:
                    //Transition of ringing->offhook are pickups of incoming calls.  Nothing donw on them
                    if (lastState != TelephonyManager.CALL_STATE_RINGING) {
                        isIncoming = false;
                        callStartTime = new Date();
                        onOutgoingCallStarted(savedNumber, callStartTime);
                    }
                    break;
                case TelephonyManager.CALL_STATE_IDLE:
                    //Went to idle-  this is the end of a call.  What type depends on previous state(s)
                    if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                        //Ring but no pickup-  a miss
                        onMissedCall(savedNumber, callStartTime);
                    } else if (isIncoming) {
                        onIncomingCallEnded(savedNumber, callStartTime, new Date());
                    } else {
                        onOutgoingCallEnded(savedNumber, callStartTime, new Date());
                        savedNumber = null;
                    }
                    break;
            }
            lastState = state;
        }
    }

}
