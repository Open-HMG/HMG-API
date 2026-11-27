# Updating HMG-API

Takes about 2 minutes. Park the car and keep it on (the head unit restarts once).

**READ THE RELEASE NOTE OF THE VERSION YOU'RE INSTALLING BEFORE PROCEEDING**

**AT YOUR OWN RISK**

**Which download?** Get it from the [Releases](https://github.com/Open-HMG/HMG-API/releases) page:

| You have | Use | Steps |
|---|---|---|
| HMG-API Update Package (Starting with 1.0.x) | `hmgapi-<version>-update.zip` | [HMG-API UPDATE](#hmg-api-update) |
| NEMESIS | `hmgapi-<version>-nemesis.zip` | [NEMESIS](#nemesis) |
| NEMESIS Package | `hmgapi-<version>-nemesis-package.zip` | [NEMESIS PACKAGE](#nemesispackage) |


All install the same thing.

## HMG-API UPDATE

If you have HMG-API 1.0.x or earlier only
1. Create a folder named `HMGAPO/updates` on the root of the USB stick.
2. Copy the file `hmgapi-<version>-update.zip`in that folder.
3. Connect the USB to the car and open the **HMG-API** app.
4. Click on **Check USB updates**.
5. Scroll to **HMG-API**, click **Update**.
6. HMG-API will close. Wait a minute.
7. Open HMG-API and click **Restart Head Unit**.

## NEMESIS

Needs NEMESIS 1.16 or newer (update NEMESIS first if Manager has no Package Store).

1. Unzip `hmgapi-<version>-nemesis.zip` onto the top level of a USB stick (FAT32 or
   exFAT): the stick gets `NEMESIS.data`, a `packages` folder and a README. Nothing
   else NEMESIS-related on the stick.
2. Plug it in once Android is up. NEMESIS Manager opens its Package Store on the
   stick's list: HMG-API and the mods it comes with. Update **HMG-API**.
3. Restart the head unit when it says so, and take the stick out.

## NEMESIS PACKAGE

Information comming soon

| Package | Can't be installed with |
|---|---|
| HMG-API | HMG Leafia Plus (`wireless.carplay.aa`), `carplay.session`, `voicehandler.vr-gates` (the last two until we know they don't clash) |
| Wireless CarPlay + Android Auto (mod) | NEMESIS's Wireless Projection (`wireless.projection`) and Projection TBT (`projection.tbt`) |
| adb (mod) | NEMESIS's ADB over TCP (`adb.tcp`) |


## After updating

**No HMG-API tile in the app menu?** The tile and the ★ choice are added by HMG-API's
hooks, so a car without a hook map yet doesn't get them. Open the app from **NEMESIS
Manager → My Packages → HMG-API → Launch** instead, or over adb:
`adb shell "hmgapid sh 'sh /data/hmgapi/open.sh'"`. Both install the app first if it's
missing.

Open **HMG-API** from the app menu (or put it on a ★ key). The bar at the top says
whether HMG-API's hooks are on for your car:

- **Hooks on**: everything works. Add mods ([Installing and removing mods](Installing-and-removing-mods)).
- **Hooks off for this car**: HMG-API still works (mods' files, settings, apps and
  services), but mods that change the car's own apps wait for your car's hook map.
  Make a [Car Report](Car-Report) and send it: that's what's needed to add your car.

A USB stick left in the port can start wired Android Auto and keep wireless from
connecting, so take it out after installing.
