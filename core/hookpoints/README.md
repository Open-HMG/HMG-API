# Hook points

Every app class, method or field that HMG-API or a mod hooks has a **stable name**
here (`launcher.menu_list.load`). Mods and HMG-API call it through `hmgapi.hook.Points`,
never by the app's own (obfuscated, per-build) name. Each car's map
(`maps/<make>/<model>/<region>/<firmware>/`) says what every name is on that car.

One file per app: `<app>.json`:

    {
      "app": "launcher",
      "package": "com.android.launcher3",          (null for the framework)
      "files": ["/system/app/Launcher_WP3.odex"],  the stock files the names come from
      "points": [ { "id", "kind", "of", "find", "about" }, ... ]
    }

`kind`: `class`, `method`, `ctor` or `field`. `of`: the class point a member belongs to.
`find` describes the thing by its **shape**, so `tools/map-car.py` can find it in any
build (it must match exactly once, or the car isn't mapped):

- class: `package` (prefix), `name` (exact, when it isn't obfuscated), `super`,
  `ctors` (lists of parameter types), `fields` (`{name?, type, static?}`),
  `methods` (`{name?, params, ret?, static?}`), `strings` (string constants in its code)
- method: `name?`, `params`, `ret?`, `static?`, `strings?`; ctor: `params`; field: `name?`, `type`, `static?`

Types are Java names (`int`, `java.lang.String`, `android.content.Context`);
`@<point id>` is another class point's class.

When a shape isn't enough (an obfuscated member reached only through another
method's code), a catalogue's `patterns` match a regex once in one class's smali
(`"in"`: a class name or `@<class point>`), and a point takes a named group:
`{"pattern", "class_group", "package"}` for a class, `{"pattern", "group", ...}` for
a method or field (the group is its name; the rest of `find` must still fit).

`files` are the stock files the names come from: the app's odex, plus any library
it declares (`uses-library`) whose classes have points, an odex or a jar holding a
plain classes.dex (`/system/framework/com.broadcom.bt.jar`). Library points are
reached with the app's class loader.

Adding a hook point means mapping it for **every** car in `maps/`: the tests fail
otherwise. Then run `tools/mkhookdoc.py`: it remakes [docs/hook-points.md](../../docs/hook-points.md),
the list of every point and the cars that have it (the tests fail if it's out of date).
