package hmgapi.engine;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

/**
 * A mod's "requires", read and matched as the engine does (Version.deps, Version.satisfies),
 * for the mod SDK's bin/mod-deps.py:
 *   Requires list <mod dir>                 one "<id> <range>" line per required mod ("<id>" alone: any version)
 *   Requires satisfies <version> <range>    exit 0 if the version is in the range, 1 if not, 2 if either is malformed
 */
public final class Requires {
    public static void main(String[] a) throws Exception {
        if (a.length == 2 && "list".equals(a[0])) {
            Map<String, Object> mj = Json.object(new String(Files.readAllBytes(new File(a[1], "mod.json").toPath()), "UTF-8"));
            for (Version.Dep d : Version.deps(mj.get("requires"))) {
                System.out.println(d.id + (d.range == null ? "" : " " + d.range));
            }
            return;
        }
        if (a.length == 3 && "satisfies".equals(a[0])) {
            try {
                System.exit(Version.satisfies(a[1], a[2]) ? 0 : 1);
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                System.exit(2);
            }
        }
        System.err.println("usage: Requires list <mod dir> | satisfies <version> <range>");
        System.exit(2);
    }
}
