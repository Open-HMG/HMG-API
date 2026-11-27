package hmgapi.engine;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The safety functions no mod touches (Protected), off the car: which app processes
 * load no mod, which classes no mod hooks, which hook points no mod declares, what
 * Bridge.hookMethod does with a mod's direct hook, and that the boot and live plans
 * (Mods.load, Mods.lateLoad) refuse a mod that declares a protected point.
 * Only the Dalvik part (Bridge.modsOff in a forked app) isn't run here.
 *
 *   ProtectedTest <hookpoints dir>          everything
 *   ProtectedTest <hookpoints dir> live     the live-load decisions only (live-test.sh)
 */
public final class ProtectedTest {
    static int fail;

    static void check(String what, boolean ok, Object got) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what + (ok ? "" : ": got " + got));
        if (!ok) fail = 1;
    }

    /** every app process seen running on a US Ioniq 5 (NE EV 2023, 260507), from its ps */
    static final List<String> IONIQ5 = Arrays.asList(
            "com.android.bluetooth", "com.android.phone", "com.hkmc.bluetooth", "com.hkmc.telematics.service.apps.climate",
            "com.android.btsettings", "com.android.buttonsettings", "com.android.displaysettings", "com.android.generalsettings",
            "com.android.inputmethod.diopen", "com.android.launcher3", "com.android.settings", "com.android.settings:remote",
            "com.android.settingscommon", "com.android.soundsettings", "com.android.systemui", "com.android.themesettings",
            "com.android.vehiclesettings", "com.daudio.app.dipo", "com.daudio.app.phoneprojection", "com.google.android.projection.sink",
            "com.hkmc.system.app.DMClient", "com.hkmc.system.app.OTAClient", "com.hkmc.system.app.engineering",
            "com.hkmc.system.app.homesetting", "com.hkmc.system.app.rearcamera", "com.hkmc.system.app.userprofile",
            "com.hkmc.telematics.Sports", "com.hkmc.telematics.Weather", "com.hkmc.telematics.app.paymentnew",
            "com.hkmc.telematics.apps", "com.hkmc.telematics.apps.Vrm.main", "com.hkmc.telematics.apps.valetMode",
            "com.hkmc.telematics.common.db", "com.hkmc.telematics.service", "com.hkmc.wifisettings", "com.mnsoft.navipopupservice",
            "com.mobis.splitscreen.right", "com.mobis.welcome", "com.tcc.IpodUsbdeviceManager", "com.tcc.iap2server",
            "com.hkmc.av.app.mediagate", "com.mnsoft.navi", "com.android.defcontainer", "com.daudio.app.findmyvehicle",
            "com.hkmc.av.app.hddata", "com.hkmc.av.app.hddata:HDDataservice", "com.hkmc.av.app.naturesound",
            "com.hkmc.av.app.radiona", "com.hkmc.mediazen.siri", "com.hkmc.daudio.app.ev3", "com.hkmc.tpegservice",
            "com.nemesis.manager", "com.SoundHound", "com.SoundHound:remote", "com.daudio.av.app.usbmusic",
            "com.mobis.voicehandler", "com.daudio.app.voicememo", "com.android.incallui", "com.android.externalstorage",
            "com.android.smspush", "com.android.HKMC_ModemFoTAService", "com.android.voicedialer", "com.android.rear_care",
            "com.daudio.av.app.btaudio.ui", "hmgapi.app", "system_server");

    static Map<String, Object> mod(String id, String points) {
        return Json.object("{\"id\": \"" + id + "\", \"version\": \"1.0.0\", \"hooks\": {\"entry\": \"x.X\", \"points\": " + points + "}}");
    }

    public static void main(String[] a) throws Exception {
        boolean liveOnly = a.length > 1 && "live".equals(a[1]);
        if (!liveOnly) processes();
        if (!liveOnly) classes();
        if (!liveOnly) direct();
        if (!liveOnly) catalogue(new File(a[0]));
        plans(liveOnly);
        System.exit(fail);
    }

    static void processes() {
        Set<String> got = new HashSet<String>();
        for (String p : IONIQ5) if (Protected.process(null, p) != null) got.add(p);
        check("US Ioniq 5's running apps: exactly the rear camera and telematics are protected",
                got.equals(new HashSet<String>(Arrays.asList("com.hkmc.system.app.rearcamera", "com.hkmc.telematics.service"))), got);
        check("the rear camera app (AVM, guide lines' screen)", Protected.process("com.hkmc.system.app.rearcamera", "com.hkmc.system.app.rearcamera") != null, null);
        check("... any process of that package", Protected.process("com.hkmc.system.app.rearcamera", "com.hkmc.system.app.rearcamera:remote") != null, null);
        check("... found by package when the process has another name", Protected.process("com.hkmc.system.app.rearcamera", "camera") != null, null);
        check("the eCall app (not on the US Ioniq 5)", Protected.process("com.hkmc.telematics.ECall", null) != null, null);
        check("a renamed camera app, by its name", Protected.process("com.mobis.avm", "com.mobis.avm") != null, null);
        check("... an emergency-call app", Protected.process(null, "com.hkmc.telematics.ecall.ui") != null, null);
        check("... not a word that only contains it (recall)", Protected.process(null, "com.hkmc.recall") == null, null);
        check("... never a mod's own app (a dashcam mod named rearcamera)", Protected.process("dev.example.rearcamera", "dev.example.rearcamera") == null, null);
        check("the launcher isn't", Protected.process("com.android.launcher3", "com.android.launcher3") == null, null);
        check("system_server isn't (its camera classes are, below)", Protected.process(null, "system_server") == null, null);
        check("remote climate (com.hkmc.telematics.service.apps.climate) isn't a process of the telematics service",
                Protected.process(null, "com.hkmc.telematics.service.apps.climate") == null, null);
        check("the reason names what runs there", String.valueOf(Protected.process(null, "com.hkmc.system.app.rearcamera")).contains("rear camera"), null);
    }

    static void classes() {
        for (String c : new String[] { "com.hkmc.micom.PgsService", "com.hkmc.micom.PgsService$CameraHandler", "com.hkmc.micom.PgsServiceV2",
                "com.hkmc.micom.TMUService$TMUServiceHandler", "com.hkmc.pgs.PgsManager", "com.hkmc.pgs.IPgsService$Stub$Proxy",
                "com.hkmc.pgs.AvmManager", "com.hkmc.tmu.TMUManager", "com.hkmc.tmu.ECallListener", "com.hkmc.micom.protocol.AVM",
                "com.hkmc.micom.protocol.camera.UNICameraProtocol", "com.hkmc.micom.protocol.TMU$TMUcan" }) {
            check("protected class " + c, Protected.hookTarget(c) != null, null);
        }
        for (String c : new String[] { "com.hkmc.micom.PgsServiceHelper", "com.hkmc.micom.MicomAudioService", "android.app.ActivityThread",
                "com.hkmc.pgsx.Other", "com.hkmc.micom.protocol.SystemProtocol" }) {
            check("not protected: " + c, Protected.hookTarget(c) == null, Protected.hookTarget(c));
        }
    }

    static void direct() {
        Protected.Direct d = Protected.direct(null, false, "com.hkmc.micom.PgsService", "com.hkmc.micom.PgsService.x", "system_server", false);
        check("HMG-API's own hooks: nothing to say", d == null, d);
        d = Protected.direct("cam", true, "com.hkmc.micom.PgsService", "com.hkmc.micom.PgsService.requestGuideLineState", "system_server", false);
        check("a mod on the guide line service, declared or not: refused", d != null && d.refused, d == null ? null : d.line);
        check("... the line says so", d != null && d.line.startsWith(Protected.MARK + "refused cam's hook on com.hkmc.micom.PgsService.requestGuideLineState in system_server"), d == null ? null : d.line);
        d = Protected.direct("cam", false, "com.hkmc.tmu.TMUManager", "com.hkmc.tmu.TMUManager.x", null, false);
        check("a mod on the telematics unit from the zygote: refused", d != null && d.refused && d.line.contains(" in zygote: "), d == null ? null : d.line);
        d = Protected.direct("ev", true, "android.app.Activity", "android.app.Activity.onCreate", "com.hkmc.system.app.rearcamera", false);
        check("any mod hook in the rear camera's process: refused", d != null && d.refused, d == null ? null : d.line);
        d = Protected.direct("ev", true, "android.app.Activity", "android.app.Activity.onCreate", "camera", true);
        check("... also when only the start said so (Bridge.modsOff)", d != null && d.refused, d == null ? null : d.line);
        d = Protected.direct("ev", true, "com.google.android.projection.sink.X", "X.shutdown", "com.google.android.projection.sink", false);
        check("a declared point's hook elsewhere: nothing to say", d == null, d);
        d = Protected.direct("egmp-aa-ev", false, "com.google.android.projection.sink.X", "X.shutdown", "com.google.android.projection.sink", false);
        check("an undeclared direct hook elsewhere: allowed", d != null && !d.refused, d == null ? null : d.line);
        check("... and reported", d != null && d.line.equals(Protected.UNDECLARED
                + "egmp-aa-ev hooked X.shutdown in com.google.android.projection.sink directly, not through a hook point it declared in mod.json"), d == null ? null : d.line);
        check("protected points: the guide lines", Protected.point("framework.pgs_service.request_guide_lines") != null, null);
        check("... the service stub", Protected.point("framework.pgs_service_stub.as_interface") != null, null);
        check("... the service's name", Protected.point("framework.car_services.pgs") != null, null);
        check("... not another car service", Protected.point("framework.car_services.power") == null, null);
    }

    /** every catalogue point on a protected class is a protected point (so a mod can't declare it) */
    @SuppressWarnings("unchecked")
    static void catalogue(File dir) throws Exception {
        Map<String, String> classOf = new LinkedHashMap<String, String>(), ofOf = new LinkedHashMap<String, String>();
        Set<String> packages = new HashSet<String>();
        File[] fs = dir.listFiles();
        Arrays.sort(fs);
        for (File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            Map<String, Object> app = Json.object(Engine.read(f));
            if (app.get("package") != null) packages.add(String.valueOf(app.get("package")));
            for (Object o : (List<Object>) app.get("points")) {
                Map<String, Object> p = (Map<String, Object>) o;
                String id = String.valueOf(p.get("id"));
                Map<String, Object> find = (Map<String, Object>) p.get("find");
                if ("class".equals(p.get("kind")) && find != null) classOf.put(id, String.valueOf(find.get("name")));
                if (p.get("of") != null) ofOf.put(id, String.valueOf(p.get("of")));
            }
        }
        check("the catalogue was read", classOf.size() > 50, classOf.size());
        int n = 0;
        for (String id : new java.util.TreeSet<String>(ofOf.keySet())) {
            String c = id;
            while (ofOf.containsKey(c)) c = ofOf.get(c);
            String cls = classOf.get(c);
            if (cls == null || Protected.hookTarget(cls) == null) continue;
            n++;
            check("catalogue point " + id + " (on " + cls + "): no mod may declare it", Protected.point(id) != null, null);
        }
        check("the catalogue has the guide line points on protected classes", n >= 3, n);
        for (String pkg : packages) check("no catalogue app is a protected process: " + pkg, Protected.process(pkg, pkg) == null, Protected.process(pkg, pkg));
    }

    static void plans(boolean liveOnly) {
        Set<String> known = new HashSet<String>(Arrays.asList("framework.pgs_service.request_guide_lines", "launcher.menu_list.load"));
        Map<String, Map<String, Object>> mods = new LinkedHashMap<String, Map<String, Object>>();
        mods.put("fine", mod("fine", "{\"launcher.menu_list.load\": \"observe\"}"));
        mods.put("lines", mod("lines", "{\"framework.pgs_service.request_guide_lines\": \"observe\"}"));
        if (!liveOnly) {
            HookPlan p = HookPlan.of(mods, known, null);
            check("boot: a mod declaring the guide line point isn't loaded, even observe and mapped", p.refused.containsKey("lines"), p.refused);
            check("... why", String.valueOf(p.refused.get("lines")).contains("a safety function, no mod hooks it (built in, no override)"), p.refused.get("lines"));
            check("... the other mod loads", !p.refused.containsKey("fine"), p.refused);
            p = HookPlan.of(mods, null, null);
            check("... also with no map to check against", p.refused.containsKey("lines"), p.refused);
        }
        Map<String, Map<String, Object>> loaded = new LinkedHashMap<String, Map<String, Object>>();
        loaded.put("fine", mods.get("fine"));
        Map<String, String> on = new LinkedHashMap<String, String>();
        on.put("fine", "1.0.0");
        on.put("lines", "1.0.0");
        HookPlan p = HookPlan.late(loaded, "lines", mods.get("lines"), on, known, null);
        check("live: a mod declaring the guide line point isn't loaded in a running app", p.refused.containsKey("lines"), p.refused);
        check("live: Mods.lateLoad loads nothing in the rear camera's process (Bridge.modsOff)",
                Protected.process("com.hkmc.system.app.rearcamera", "com.hkmc.system.app.rearcamera") != null, null);
        Map<String, Object> other = mod("other", "{\"launcher.menu_list.load\": \"change\"}");
        p = HookPlan.late(loaded, "other", other, on, known, null);
        check("live: an ordinary mod still loads", !p.refused.containsKey("other"), p.refused);
    }
}
