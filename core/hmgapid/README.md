# hmgapid

HMG-API's root daemon and its client, one static ARM binary (no libc, raw syscalls).

```
hmgapid daemon            # needs full root, refuses otherwise
hmgapid boot              # the boot hook: returns at once; detached, starts the daemon and runs /data/hmgapi/boot.sh
hmgapid <op> [args...]    # any user: asks the daemon, prints its output, exits with its code
```

| Caller (SO_PEERCRED) | Ops |
|---|---|
| root (0), shell (2000: adb) | everything: `sh <command>`, `remount rw\|ro`, plus the HMG-API ops |
| the `hmgapi.app` uid (from `/data/system/packages.list`) | `mods`, `enable <id>`, `disable <id>`, `setting-get <id>`, `setting-set <id> <json>`, `log`, `reboot [--gear-unknown-ok]` (only in P: 75 not in P; 76 no gear reading, unless told to go on), `allow`, `park-rule`, `services [mod]`, `service-restart\|stop\|start <mod> <name>`, `service-log <mod> <name>`, `usb [mod]`, `vehicle <reading>…` |
| an enabled mod's app (its `apps`, `state/apps.txt`) or service (by session) | its own mod only: `whoami`, `setting-get`, `setting-set <json>`, `services`, `service-restart\|stop\|start <name>`, `service-log <name>`, `usb`, `vehicle <reading>…` (the readings its mod.json lists) |
| anyone else | `ping`, `version` |

**Services.** The daemon runs the enabled mods' services (`state/services.txt`, written by the engine from `mod.json`): as root, system or the mod's app uid, each in its own session, with its own folder (`run/<mod>/<name>`) and log (`logs/<mod>/<name>.log`), started at boot or once `sys.boot_completed` is 1, restarted with backoff, stopped (TERM, then KILL) when the mod is turned off. It also runs `engine apps` once Android is up and whenever `state/apps.txt` changes.

A service's output comes to the daemon through a pipe, so its log is capped while it runs: 256 KB, then `.1`, and at most 256 KB of output kept a minute (the rest left out, with a line saying so and how much; before, the log was only checked when the service started, and a looping `echo` wrote 885 MB in 8 s). HMG-API's own log (`hmgapi log`, LogKeeper) is one of these services, and LogKeeper keeps each mod's lines to 300 a minute, so HMG-API's own lines stay. A mod's service runs at nice 10 with the lowest best-effort I/O priority (`setpriority`, `ioprio_set`) unless its `mod.json` asks for `"priority": "normal"` (`state/service-priority.txt`); HMG-API's own run at normal priority. Only fds 0-2 reach a service.

**Safe mode.** While `/data/hmgapi/SAFE` is there (`engine safe on`, the app's switch, the zygote wrapper's boot guard, or the file by hand), the daemon keeps only HMG-API's own lines of `services.txt`: every mod's service stops, boot-start ones included, and none starts, even right after a restart before the engine has run. When the file comes or goes it has the engine publish the state again (every mod off: apps, network rules, the public state) and runs `engine apps`. `engine` is open to root, shell and the HMG-API app, so `hmgapid engine safe on|off` works from adb and from the app.

**Boot.** `hmgapid boot` is the boot hook's only line (`install-recovery.sh`, `qb_runtime.sh`) and what HMG-API's NEMESIS service runs. It returns at once, so the boot script goes straight on to NEMESIS's start. A detached child (own session, none of the caller's fds) starts the daemon if none runs (the zygote wrapper usually started it already) and, if it's the first call this boot, runs `/data/hmgapi/boot.sh` (HMG-API's boot steps: binds, the app's installer, `hook-check.sh`, the NEMESIS sync, `engine recover`; output in `boot.log`). The lock is `/dev/.hmgapi-boot`, made with `O_EXCL` on tmpfs (gone at every boot). So whichever trigger comes first runs the boot steps, and the others do nothing.

Abstract socket `@hmgapid` (no file permissions involved; the bind is also the single-instance lock). Request `LFD1` + argc + length-prefixed args; response frames `O` (output, streamed) and `X` (exit code). Log: `/data/hmgapi/hmgapid.log`.

Started from NEMESIS's daemon (`native/su.c`, `util.c`); what reviewing it turned up and how hmgapid differs:

- any app got root (sockets `0666`, peer uid only logged) → ops per caller
- the pidfile "already running" check matched an unrelated process with the same pid and start time (seen on a car: the daemon was gone for a boot) → no pidfile
- `umask 0` left files world-writable → `umask 077`, and `/data/hmgapi` folders are set to `0700` at every start
- `/system` remounted rw at every start and left rw → only on request
- output buffered until the command exits, silently cut at 512 KB → streamed
- a client that sent nothing held a forked worker forever → 30 s request timeout, at most 8 workers
- started from adb it ran with adbd's capability set (CapEff `0xc0`) → refuses to start without full root

`./build.sh` → `out/hmgapid`. `./test.sh` runs the real daemon and client under qemu-arm against a temp root (the four caller kinds, streaming, 220 KB output, mod and settings ops, services (start, backoff, restart, stop, removal, identity by session), USB folders, bad ids, single instance, oversized and malformed requests).

Tested on a US EV6 (260507), 2026-10-27, started through the NEMESIS daemon (no boot hook yet): refuses weak root, `version`, `mods`, `sh` (CapEff `3fffffffff`, exit codes).

**Vehicle readings.** `vehicle <name>…` checks each name against
`state/vehicle.txt` (`<mod> <reading>`, written by the engine from each enabled mod's
`"vehicle": {"read": [...]}`) and passes the request to HMG-API's vehicle service
(`hmgapi.vehicle.Hub`, abstract socket `@hmgapi-vehicle`, root callers only), which
answers `<name> <value>` lines. hmgapid runs that service (mod `hmgapi`, the only
service allowed to run `/system/framework/hmgapi-hook.jar`) while any enabled mod
declares readings.

**Car events.** A mod's event script (`"car_events"`) is one of its services, `on-<event>`,
start `manual`, no restart, the event as its argument; the vehicle service starts it
through `state/ctl` when the event comes and watches it in `services.status` (it puts the
service back to `stopped` after each run, so the next `exited` is the next run's), and stops
it when its time is up. Which mods asked: `state/events.txt` (`<mod> <event> <service|->`).
`park-rule [door|closed|<10-1800>]` (root, shell, the HMG-API app): the owner's rule for
when the car counts as parked, in `settings/park_rule`; no argument: the rule now (`door`
until the owner picks).

**Network keeper.** HMG-API's other own service, `hmgapi net`, runs
`state/net.sh` (the only script a `hmgapi` service may run; the engine writes it,
`hmgapi.engine.Network`) while any enabled mod has `"network"` asks or some are still
in: it applies `state/net.rules`, re-applies what the stock firewall removes, and takes
everything out when it's stopped (TERM).
