# Hyundai Ioniq 5 — NEEV23 USA 260507

This map is probed from an NEEV23.USA.0000.V015.012.260507 head unit, Android 4.4.2, tcc803x. Its fingerprints come from a Car Report after uninstalling Leafia and the previous file-patched EV-routing extension, leaving NEMESIS 1.16 installed. No OEM binaries or per-car identifiers are included.

The original 325-point map passed `engine verify-map` on that unit. Thirteen entries were matched by other cars' names; these remain listed in `car.json` under `by_reference`. The six new iAP2 entries were resolved against stock smali and exercised as part of an EV-routing test. The complete 331-point catalogue/map passes the off-car completeness checker.

Wireless CarPlay was confirmed working with HMG-API's Wireless CarPlay + Android Auto mod. With a local beta5 build containing the six catalogue additions, an experimental EV mod registered its stock iAP2 listener and repeatedly sent live VehicleStatus messages successfully (2026-11-02). This validates the EV path, not every mapped feature.

Status remains `probed`, with no global verification claim. Reconnect/long-drive EV reliability and other mapped features need further testing. A retained log also showed the wireless mod's Phone Projection settings shortcut targeting an unavailable Activity on this firmware; successful hook-name resolution does not establish that every shortcut works. No changes to that shortcut are included here.
