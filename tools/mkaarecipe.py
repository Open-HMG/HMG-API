#!/usr/bin/env python3
"""mkaarecipe.py - HMG-API's core Android Auto library recipe (aa-raw) for one build of
libautoreceiver_infobank.so: sensor types 22-26 parse, a registered sensor outside the
stock 1..21 is answered SUCCESS, and SensorSource.nativeReportDiagnosticsData sends a
whole raw Android Auto message (ProjectionHooks.sendRaw).

usage: mkaarecipe.py <libautoreceiver_infobank.so> <out.recipe>
           [--sites ISVALID,DEFAULT,REPORT --queue QUEUE --id aa-raw-<md5, 8 chars>]
           [--avoid RECIPE...]

Without options: the US 260507 build (md5 48ee793a...). The input is the stock library,
or a build whose bytes at the four verify windows below are stock (NEMESIS's
projection.tbt build c08410a8... is: it changes the library only inside the Wireless
mod's aa-tbt-48ee793a windows). The recipe names the stock build either way.

The three sites (file offsets on 48ee793a):
  0x144ec2  SensorType_IsValid       cmp r0,#20 -> cmp r0,#25      types 1..26 parse (23: EV energy model, 26: egmp-aa-ev's battery % curve)
  0xbb29e   handleSensorRequest      mvn.w r7,#8 -> mov.w r7,#0    SUCCESS, not -9, for a registered type outside 1..21
  0xbb638   reportDiagnosticsData    push.w -> b.w QUEUE           tail call into the endpoint's queueOutgoing
QUEUE (0xb1972 on 48ee793a) is the sensor endpoint's queueOutgoing(this, data, len): it
sends when the channel is open and does nothing otherwise. It is checked, never written.

Another build: find the same three instructions and queueOutgoing in it (the dynamic
symbols _Z18SensorType_IsValidi, _ZN12SensorSource19handleSensorRequestERK13SensorRequest
and _ZN12SensorSource21reportDiagnosticsDataEPhj, and a disassembler), then pass their
offsets, an --id after the stock build's md5, and the Wireless mod's recipe for that build
as --avoid. This refuses a build whose bytes at the sites aren't these instructions, and
verify windows that overlap an --avoid recipe's (HMG-API doesn't apply a recipe that
overlaps one already on the library). Verify windows: 16 bytes from 8 before each site
(10 before the first on 48ee793a, as #44 has them), 24 at queueOutgoing. Add the output
to core/recipes and hmgapi.hook.CoreRecipes, with an "# args:" line giving the options
(aa-raw-test.sh makes it again from a car tree with that build).

The recipe holds no manufacturer bytes: md5s of the verify windows and the bytes we write.
"""
import argparse, hashlib, re, struct, sys

# (what, stock bytes, new bytes or None for the branch, verify window start before the site)
SITES = [
    ("SensorType_IsValid: sensor types 1..26 parse (23: vehicle energy model)", bytes.fromhex("1428"), bytes.fromhex("1928"), 10),
    ("handleSensorRequest default: SUCCESS for a registered type outside 1..21, not -9", bytes.fromhex("6ff00807"), bytes.fromhex("4ff00007"), 8),
    ("reportDiagnosticsData: tail-branch into queueOutgoing (raw messages)", bytes.fromhex("2de9f041"), None, 8),
]
US = {"sites": (0x144EC2, 0xBB29E, 0xBB638), "queue": 0xB1972, "md5": "48ee793af8b86052503b8c952b034731",
      # builds whose windows are the stock bytes of 48ee793a: the stock file, NEMESIS's projection.tbt
      "same": ("48ee793af8b86052503b8c952b034731", "c08410a8d32a615b1aa805cd8a243d52"),
      # the Wireless mod's aa-tbt-48ee793a verify regions on this build
      "avoid": [(0xB1A1E, 33), (0xB6808, 36), (0x144EEA, 33)]}
# queueOutgoing starts: push {r4, r5, lr}; mov r4, r0; ldrb r0, [r0, #4] (the channel-open flag)
QUEUE_START = bytes.fromhex("30b504460079")
WINDOW, QUEUE_WINDOW = 16, 24


def b_w(at, to):
    """Thumb-2 B.W (T4) at file offset at to file offset to"""
    off = to - (at + 4)
    if off % 2 or not -(1 << 24) <= off < (1 << 24):
        sys.exit("b.w from %#x to %#x out of range" % (at, to))
    s = 1 if off < 0 else 0
    i1, i2 = (off >> 23) & 1, (off >> 22) & 1
    j1, j2 = 1 - (i1 ^ s), 1 - (i2 ^ s)
    hw1 = 0xF000 | (s << 10) | ((off >> 12) & 0x3FF)
    hw2 = 0x9000 | (j1 << 13) | (j2 << 11) | ((off >> 1) & 0x7FF)
    return struct.pack("<HH", hw1, hw2)


def regions_of(path):
    return [(int(m.group(1), 0), int(m.group(2), 0)) for m in
            (re.match(r"\s*verify\s+(\S+)\s+(\S+)", l) for l in open(path)) if m]


md5 = lambda b: hashlib.md5(b).hexdigest()


def main():
    ap = argparse.ArgumentParser(description="HMG-API's core Android Auto library recipe")
    ap.add_argument("lib")
    ap.add_argument("out")
    ap.add_argument("--sites", help="IsValid cmp, handleSensorRequest default, reportDiagnosticsData push (file offsets)")
    ap.add_argument("--queue", help="queueOutgoing (file offset)")
    ap.add_argument("--id", help="aa-raw-<the stock build's md5, 8 chars>")
    ap.add_argument("--avoid", nargs="*", help="other recipes on this build whose regions must stay clear")
    a = ap.parse_args()
    src = open(a.lib, "rb").read()
    if a.sites or a.queue or a.id:
        if not (a.sites and a.queue and a.id):
            sys.exit("--sites, --queue and --id go together")
        sites = tuple(int(x, 0) for x in a.sites.split(","))
        if len(sites) != 3:
            sys.exit("--sites: three offsets")
        queue, rid, avoid, built = int(a.queue, 0), a.id, [], md5(src)
    else:
        if md5(src) not in US["same"]:
            sys.exit("%s (md5 %s) isn't the US 260507 build: pass its --sites, --queue and --id" % (a.lib, md5(src)))
        sites, queue, rid, avoid, built = US["sites"], US["queue"], "aa-raw-" + US["md5"][:8], US["avoid"], US["md5"]
    if a.avoid is not None:
        avoid = [r for f in a.avoid for r in regions_of(f)]
    after = bytearray(src)
    windows = []
    for off, (what, old, new, before) in zip(sites, SITES):
        if src[off:off + len(old)] != old:
            sys.exit("%s: bytes at %#x are %s, expected %s (not a build these patches are for)"
                     % (what, off, src[off:off + len(old)].hex(), old.hex()))
        after[off:off + len(old)] = new if new is not None else b_w(off, queue)
        windows.append((off - (before if a.sites is None else 8), WINDOW, what))
    if src[queue:queue + len(QUEUE_START)] != QUEUE_START:
        sys.exit("queueOutgoing at %#x starts %s, expected %s" % (queue, src[queue:queue + 6].hex(), QUEUE_START.hex()))
    windows.append((queue, QUEUE_WINDOW, "queueOutgoing: the branch target, checked, unchanged"))
    for w, n, _ in windows:
        for o, m in avoid:
            if w < o + m and o < w + n:
                sys.exit("verify window %#x+%d overlaps another recipe's region %#x+%d" % (w, n, o, m))
    lines = [
        "# HMG-API core: Android Auto library, sensor types 22-26 and raw messages (ProjectionHooks.sendRaw).",
        "# For the stock build %s; its verify windows mismatch on any other build." % built,
        "# generated by tools/mkaarecipe.py (memory only: hmgapi.engine.MemRecipe)",
        "id %s" % rid,
        "file /system/lib/libautoreceiver_infobank.so",
    ]
    for w, n, what in windows:
        lines.append("# " + what)
        lines.append("verify %#x %d %s %s" % (w, n, md5(src[w:w + n]), md5(bytes(after[w:w + n]))))
    for off, (_, old, _, _) in zip(sites, SITES):
        lines.append("patch %#x %s" % (off, bytes(after[off:off + len(old)]).hex()))
    open(a.out, "w").write("\n".join(lines) + "\n")
    print("wrote %s (%s) from %s" % (a.out, rid, a.lib))


if __name__ == "__main__":
    main()
