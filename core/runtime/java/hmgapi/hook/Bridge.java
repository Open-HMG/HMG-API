package hmgapi.hook;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * The Java half of the hook runtime (libhmgapi_hook.so is the native half).
 * Loaded into the zygote by the native loader, which registers the natives
 * and then calls {@link #init()}. Every app forked afterwards has it.
 */
public final class Bridge {
    private Bridge() {}

    static native void log(int priority, String message);

    /** native patches (NativePatch): a copy of this process's memory, a code write (0 ok), a code page run (0: none) */
    static native byte[] peekNative(long addr, int len);

    static native int pokeNative(long addr, byte[] data);

    static native long mapCodeNative(long hint, int len);

    private static native void hookMethodNative(Member method, Class<?> declaringClass, int slot, Object hookList);

    private static native Object invokeOriginalMethodNative(Member method, int hookInfo, Class<?>[] parameterTypes,
            Class<?> returnType, Object thisObject, Object[] args)
            throws IllegalAccessException, IllegalArgumentException, InvocationTargetException;

    static final class HookList {
        final Member member;
        final Class<?>[] parameterTypes;
        final Class<?> returnType;
        volatile MethodHook[] hooks = new MethodHook[0];

        HookList(Member member, Class<?>[] parameterTypes, Class<?> returnType) {
            this.member = member;
            this.parameterTypes = parameterTypes;
            this.returnType = returnType;
        }
    }

    private static final Map<Member, HookList> hooked = new HashMap<Member, HookList>();

    /**
     * Set in a protected app process (hmgapi.engine.Protected: the cameras, guide lines,
     * telematics) before its own code runs: every mod's hook passes straight through
     * there, including the ones mods put on in the zygote, and no mod may add one.
     */
    static volatile boolean modsOff;
    /** the direct-hook lines this process already logged (each once) */
    private static final java.util.Set<String> said = new java.util.HashSet<String>();

    /** Called once by the native loader, in the zygote. */
    static void init() {
        try {
            HmgApi.zygoteInit();
        } catch (Throwable t) {
            log(6, "zygote init failed: " + trace(t));
        }
    }

    /** Called by the native loader instead of init() in test mode (HMGAPI_SELFTEST). */
    static void selfTest() {
        SelfTest.selfTest();
    }

    /**
     * Adds a hook to a method or constructor. Several hooks on one method run in the order added.
     * Mods hook through ModContext.hook (a point declared in mod.json). A mod calling this
     * directly is refused in a protected process and on a safety function's classes
     * (hmgapi.engine.Protected), and logged everywhere else ("undeclared hook:", in the Car Report).
     */
    public static synchronized void hookMethod(Member member, MethodHook hook) {
        if (!(member instanceof Method) && !(member instanceof Constructor)) {
            throw new IllegalArgumentException("only methods and constructors can be hooked: " + member);
        }
        if (Modifier.isAbstract(member.getModifiers())) {
            throw new IllegalArgumentException("abstract methods can't be hooked: " + member);
        }
        // whose hook: a mod's (by its class loader) or HMG-API's own. A mod's is refused in a
        // protected process and on a safety function's classes, declared or not; one outside
        // its declared points is logged for the Car Report (Protected.direct)
        String mod = ownerOf(hook);
        if (mod != null) {
            String cls = member.getDeclaringClass().getName();
            String proc = processName();
            hmgapi.engine.Protected.Direct d = hmgapi.engine.Protected.direct(mod, hook instanceof ModContext.Guard, cls,
                    cls + "." + member.getName(), proc, modsOff);
            if (d != null) {
                if (said.add(d.line)) log(5, d.line);
                if (d.refused) throw new IllegalStateException(d.line);
            }
        }
        hook.owner = mod;
        HookList list = hooked.get(member);
        if (list == null) {
            Class<?>[] params;
            Class<?> ret;
            if (member instanceof Method) {
                params = ((Method) member).getParameterTypes();
                ret = ((Method) member).getReturnType();
            } else {
                params = ((Constructor<?>) member).getParameterTypes();
                ret = void.class;
            }
            list = new HookList(member, params, ret);
            hookMethodNative(member, member.getDeclaringClass(), slotOf(member), list);
            hooked.put(member, list);
        }
        // after every hook of the same or lower priority (so replace hooks come last)
        MethodHook[] old = list.hooks;
        MethodHook[] now = new MethodHook[old.length + 1];
        int at = old.length;
        while (at > 0 && old[at - 1].priority > hook.priority) at--;
        System.arraycopy(old, 0, now, 0, at);
        now[at] = hook;
        System.arraycopy(old, at, now, at + 1, old.length - at);
        list.hooks = now;
    }

    /** the mod whose code a hook is, or null for HMG-API's own */
    static String ownerOf(MethodHook hook) {
        if (hook instanceof ModContext.Guard) return ((ModContext.Guard) hook).mod.id;   // a declared point's
        ClassLoader l = hook.getClass().getClassLoader();
        if (l == Bridge.class.getClassLoader()) return null;
        for (Map.Entry<String, ClassLoader> e : Mods.loaders.entrySet()) if (e.getValue() == l) return e.getKey();
        for (Map.Entry<String, ClassLoader> e : Mods.parts.entrySet()) {
            if (e.getValue() == l) return e.getKey().substring(0, e.getKey().indexOf('/'));   // "<mod>/<package>"
        }
        return "an unknown caller (" + hook.getClass().getName() + ")";
    }

    /** this process's name (/proc/self/cmdline), or null in the zygote */
    static String processName() {
        try {
            byte[] b = Mods.readBytes(new java.io.File("/proc/self/cmdline"));
            int n = 0;
            while (n < b.length && b[n] != 0) n++;
            String s = new String(b, 0, n, "UTF-8");
            return s.isEmpty() || "zygote".equals(s) ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static int slotOf(Member member) {
        try {
            Field f = member.getClass().getDeclaredField("slot");
            f.setAccessible(true);
            return f.getInt(member);
        } catch (Exception e) {
            throw new IllegalStateException("no slot field on " + member.getClass(), e);
        }
    }

    /** Called by the native hook callback instead of the original method. */
    private static Object handleHookedMethod(Member member, int hookInfo, Object info, Object thisObject,
            Object[] args) throws Throwable {
        HookList list = (HookList) info;
        MethodHook[] hooks = list.hooks;
        if (hooks.length == 0) {
            return invokeOriginal(list, hookInfo, thisObject, args);
        }
        MethodHook.Param param = new MethodHook.Param(member, thisObject, args);
        int ran = 0;
        while (ran < hooks.length) {
            if (modsOff && hooks[ran].owner != null) {   // a protected process: mods' hooks pass straight through
                ran++;
                continue;
            }
            try {
                hooks[ran].beforeHookedMethod(param);
            } catch (Throwable t) {
                log(6, "before hook of " + member + " failed: " + trace(t));
                param.reset();
            }
            ran++;
            if (param.returnEarly) break;
        }
        if (!param.returnEarly) {
            try {
                param.setResult(invokeOriginal(list, hookInfo, thisObject, param.args));
            } catch (InvocationTargetException e) {
                param.setThrowable(e.getCause());
            }
        }
        for (int i = ran - 1; i >= 0; i--) {
            if (modsOff && hooks[i].owner != null) continue;
            Object lastResult = param.getResult();
            Throwable lastThrowable = param.getThrowable();
            try {
                hooks[i].afterHookedMethod(param);
            } catch (Throwable t) {
                log(6, "after hook of " + member + " failed: " + trace(t));
                if (lastThrowable == null) param.setResult(lastResult);
                else param.setThrowable(lastThrowable);
            }
        }
        return param.getResultOrThrowable();
    }

    private static Object invokeOriginal(HookList list, int hookInfo, Object thisObject, Object[] args)
            throws Throwable {
        return invokeOriginalMethodNative(list.member, hookInfo, list.parameterTypes, list.returnType, thisObject,
                args);
    }

    static String trace(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString();
    }
}
