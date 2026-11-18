package dev.hmgapi.example.carcheck;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import hmgapi.client.HmgApi;

/**
 * HMG-API test mod: every car event (parked, unparked, shutdown, wake), as the car's notice and
 * in the log (tag CarCheck). Declared with android:permission DEVICE_POWER: only HMG-API (root)
 * or the system can send it. The mod's script for each event writes to its own log too
 * (hmgapid service-log car-check on-<event>).
 */
public class CarEvent extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String text = "Car event: " + i.getStringExtra(HmgApi.EXTRA_EVENT);
        Log.i("CarCheck", text);
        HmgApi.toast(c, text);
    }
}
