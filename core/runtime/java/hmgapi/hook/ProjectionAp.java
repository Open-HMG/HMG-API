package hmgapi.hook;

import android.net.wifi.WifiConfiguration;

/**
 * The access point wireless CarPlay and Android Auto join (HKMC's "connectivity" AP,
 * apMode 2), with the name and key the phone is given.
 *
 * The projection apps (PhoneProjection, the Wireless mod) start it with
 * WifiManager.setWifiApEnabledForConnectivity, which marks the config apMode 2, and
 * tell the phone the config's ConnectivitySSID / ConnectivityPSK. On most builds
 * NetworkManagementService.startAccessPoint then runs "softap set" with those
 * ("start softap for wireless connectivity"). The older Hyundai and Kia builds
 * (US 260507, Canada 260206, Korea 260902: hmc, kmc) have no such branch: the AP comes
 * up with the hotspot's own name, key and channel, so the phone never finds the network
 * it was told to join. Seen on a Korean K5 Hybrid (DL3HEV22, #159).
 *
 * In system_server, before startAccessPoint: an apMode 2 config whose connectivity
 * name or key differs from the hotspot's goes on as a copy with them in SSID and
 * preSharedKey. The branch also takes the config's channel (the build's own 5 GHz one)
 * even when the owner's hotspot is on 2.4 GHz (is24GHzBand), so the copy has that off
 * too; wifi.info.channel still overrides, as on stock. On builds with the branch
 * nothing changes (it reads the connectivity fields and channel itself, or falls back
 * to the hotspot path, which now gets the same ones).
 * The saved hotspot settings (softap.conf) are never touched: WifiApConfigStore
 * saved the config before this runs, and the copy isn't saved.
 */
final class ProjectionAp {
    private ProjectionAp() {}

    static final String START = "framework.network_management_service.start_access_point";
    static final String MODE = "framework.wifi_configuration.ap_mode";
    static final String SSID = "framework.wifi_configuration.connectivity_ssid";
    static final String PSK = "framework.wifi_configuration.connectivity_psk";
    static final String BAND24 = "framework.wifi_configuration.is_24ghz_band";

    /** In the zygote: the hook is inherited by system_server, the only caller. */
    static void zygote() {
        if (!Points.needAll("projection AP", START, MODE, SSID, PSK, BAND24)) return;
        Points.hook(null, START, new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                try {
                    Object c = p.args[0];
                    if (!(c instanceof WifiConfiguration)) return;
                    WifiConfiguration w = (WifiConfiguration) c;
                    Object mode = Points.get(null, MODE, w);
                    if (!(mode instanceof Integer) || (Integer) mode != 2) return;
                    String ssid = (String) Points.get(null, SSID, w);
                    String psk = (String) Points.get(null, PSK, w);
                    if (ssid == null || ssid.length() == 0) return;
                    boolean sameKey = psk == null || psk.equals(w.preSharedKey);
                    boolean band24 = Boolean.TRUE.equals(Points.get(null, BAND24, w));
                    if (ssid.equals(w.SSID) && sameKey && !band24) return;
                    WifiConfiguration copy = (WifiConfiguration) WifiConfiguration.class
                            .getConstructor(WifiConfiguration.class).newInstance(w);
                    copy.SSID = ssid;
                    if (psk != null) copy.preSharedKey = psk;
                    if (band24) Points.field(null, BAND24).setBoolean(copy, false);
                    p.args[0] = copy;
                    HmgApi.i("projection AP: started with its connectivity name" + (sameKey ? "" : " and key")
                            + (band24 ? " on its own channel, not the hotspot's 2.4 GHz one" : "")
                            + " (this build would use the hotspot's)");
                } catch (Throwable t) {
                    HmgApi.e("projection AP", t);
                }
            }
        });
    }
}
