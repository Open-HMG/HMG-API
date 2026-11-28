package hmgapi.engine;

/** Exercises the boot keeper's radio checks without depending on interface addresses. */
public final class WifiApTest {
    public static final class Wifi {
        int ap, client, mode, starts;
        int nextAp = -1, startMode = 2;
        boolean savedClient;

        Wifi(int ap, int client, int mode) {
            this.ap = ap;
            this.client = client;
            this.mode = mode;
        }

        public int getWifiApEnabledState() {
            int state = ap;
            if (nextAp != -1) {
                ap = nextAp;
                nextAp = -1;
            }
            return state;
        }

        public int getWifiApMode() { return mode; }
        public int getWifiEnabledState() { return client; }
        public boolean getPersistedWifiEnabled() { return savedClient; }

        public void setWifiApEnabledForConnectivity(Object config, boolean enabled) {
            if (!enabled) throw new AssertionError("boot keeper stopped an AP");
            starts++;
            ap = 13;
            mode = startMode;
        }
    }

    private static void check(String name, Wifi wifi, int status, int starts) throws Exception {
        int result = WifiAp.ensureProjection(Wifi.class, wifi, Object.class);
        if (result != status || wifi.starts != starts) {
            throw new AssertionError(name + ": status " + result + ", starts " + wifi.starts);
        }
        System.out.println("ok    " + name);
    }

    /** WifiManager as a mod's app process sees it (the attach path) */
    public static final class Manager {
        boolean on;
        int mode, starts;
        Object startedWith;
        final Object saved = new Object();

        Manager(boolean on, int mode) {
            this.on = on;
            this.mode = mode;
        }

        public boolean isWifiApEnabled() { return on; }
        public int getWifiApMode() { return mode; }
        public Object getWifiApConfiguration() { return saved; }

        public boolean setWifiApEnabledForConnectivity(Object config, boolean enabled) {
            if (!enabled) throw new AssertionError("attach stopped an AP");
            starts++;
            startedWith = config;
            on = true;
            mode = 2;
            return true;
        }
    }

    private static void attach(String name, Manager m, String home, String says, int starts) throws Exception {
        String r = WifiAp.attachProjection(m, Object.class, home);
        if (!r.startsWith(says) || m.starts != starts) {
            throw new AssertionError(name + ": \"" + r + "\", starts " + m.starts);
        }
        if (starts > 0 && m.startedWith != m.saved) throw new AssertionError(name + ": not started with the saved config");
        System.out.println("ok    " + name);
    }

    /** WifiAp.crc: the checksum file the car's Wi-Fi library checks (Ioniq 5 US 260507: its own pair below). */
    private static void crcChecks() throws Exception {
        java.io.File d = java.nio.file.Files.createTempDirectory("crc").toFile();
        java.io.File conf = new java.io.File(d, "wpa_supplicant.conf"), crc = new java.io.File(d, "wpa_supplicant_crc32.conf");
        // the car's template (/system/etc/wifi/wpa_supplicant.conf, 98 bytes) and the checksum its library wrote for it
        String template = "update_config=1\nctrl_interface=wlan0\neapol_version=1\nap_scan=1\nfast_reauth=1\np2p_disabled=1\npmf=1\n";
        java.nio.file.Files.write(conf.toPath(), template.getBytes("US-ASCII"));
        java.io.PrintStream was = System.out;
        System.setOut(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        try {
            if (WifiAp.crc(conf) != 0) throw new AssertionError("crc: failed");
            String got = Engine.read(crc);
            if (!"54931C7F".equals(got)) throw new AssertionError("crc: the car's library wrote 54931C7F for its template, got [" + got + "]");
            long t = crc.lastModified();
            crc.setLastModified(t - 100000);
            WifiAp.crc(conf);
            if (crc.lastModified() != t - 100000) throw new AssertionError("crc: rewritten though it matched");
            java.nio.file.Files.write(conf.toPath(), (template + "\nnetwork={\n\tssid=\"x\"\n\tkey_mgmt=NONE\n}\n").getBytes("US-ASCII"));
            WifiAp.crc(conf);
            if ("54931C7F".equals(Engine.read(crc)) || Engine.read(crc).length() != 8) throw new AssertionError("crc: not updated after an edit");
            if (WifiAp.crc(new java.io.File(d, "none")) == 0) throw new AssertionError("crc: no networks file is not ok");
            // homewifi-test.sh's chown fails for a path with "chownfail" in it
            java.io.File bad = new java.io.File(d, "chownfail");
            bad.mkdir();
            java.io.File badConf = new java.io.File(bad, "wpa_supplicant.conf");
            java.nio.file.Files.write(badConf.toPath(), template.getBytes("US-ASCII"));
            boolean threw = false;
            try {
                WifiAp.crc(badConf);
            } catch (java.io.IOException x) {
                threw = x.getMessage().contains("chown");
            }
            if (!threw) throw new AssertionError("crc: a chown that fails is an error that names it");
            String[] left = bad.list();
            if (left.length != 1) throw new AssertionError("crc: after a failure only the networks file, got " + java.util.Arrays.toString(left));
        } finally {
            System.setOut(was);
        }
        System.out.println("ok    WifiAp crc: the car's checksum, 8 upper-case digits, written only when it changed");
        System.out.println("ok    WifiAp crc: a chown that fails is said, nothing left behind");
    }

    public static void main(String[] args) throws Exception {
        crcChecks();
        attach("attach: projection AP already up is left alone", new Manager(true, 2), null, "already up", 0);
        attach("attach: AP off is started in mode 2", new Manager(false, 0), "off", "started in mode 2", 1);
        attach("attach: plain hotspot is switched to mode 2 (as ensureHotspot did)", new Manager(true, 1), null, "started in mode 2", 1);
        attach("attach: home Wi-Fi joined while parked keeps the radio", new Manager(false, 0), "up", "not started", 0);
        attach("attach: home Wi-Fi searching while parked keeps the radio", new Manager(false, 0), "searching", "not started", 0);
        attach("attach: home Wi-Fi waiting (car on) doesn't block", new Manager(false, 0), "waiting", "started in mode 2", 1);
        check("personal hotspot with no IP address is left alone", new Wifi(13, 1, 1), 0, 0);
        check("projection AP already on is left alone", new Wifi(13, 1, 2), 0, 0);
        check("AP enabling is left to finish", new Wifi(12, 1, 0), 1, 0);
        check("AP disabling is left to finish", new Wifi(10, 1, 1), 1, 0);
        check("active Wi-Fi client keeps the radio", new Wifi(11, 3, 0), 0, 0);
        check("client enabling is left to finish", new Wifi(11, 2, 0), 1, 0);
        Wifi restoringClient = new Wifi(11, 1, 0);
        restoringClient.savedClient = true;
        check("saved Wi-Fi client can restore at boot", restoringClient, 0, 0);
        Wifi startingAp = new Wifi(11, 1, 0);
        startingAp.nextAp = 12;
        check("factory AP start between reads is left alone", startingAp, 1, 0);
        check("idle radio starts mode 2 once", new Wifi(11, 1, 0), 0, 1);
        Wifi wrongMode = new Wifi(11, 1, 0);
        wrongMode.startMode = 1;
        check("start only succeeds when mode 2 comes up", wrongMode, 1, 1);
    }
}
