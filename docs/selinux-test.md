# Testing: SELinux Permissive at start

Most Gen5W head units start with SELinux off. Some newer ones (we think the Ioniq 6)
start with it **Enforcing**, which can stop HMG-API's hooks and mods from working.
This test build adds an option to set SELinux to Permissive early in each start. We
need owners of those cars to check it, because none of the maintainers has one.

It takes about 30 minutes, parked, with a laptop. Everything here can be undone.

## What you need

- A head unit with **NEMESIS 1.16.8 or newer** installed.
- A laptop with **adb** ([Android platform-tools](https://developer.android.com/tools/releases/platform-tools)).
- A **USB-A to USB-A (male to male) cable**.
- The test build: `hmgapi-<version>-adb.zip` from the test build's release page. Unzip it on the laptop.
- The car in **P**, parking brake on, somewhere you can sit for a while. Never press SOS.

## Before you start: turn on USB ADB (your way back)

In NEMESIS Manager, open the **ADB** tile and turn on **USB ADB**. Connect the laptop
with the male-to-male cable and check that the head unit shows up:

```
adb devices
```

Leave USB ADB on for the whole test. It works even when Android doesn't start, so
it's how you undo things if something goes wrong. While it's on, the USB port won't
recognise phones (wired CarPlay and Android Auto) or USB sticks. Turn it off in the
same tile when you're done.

Run every `./hmgapi-adb.sh` command below from the unzipped folder.

## 1. Before installing

```
./hmgapi-adb.sh selinux
```

This only reads, and saves what it finds to `selinux-check-<date>.txt`. Look at the
`mode:` line:

- **Enforcing**: carry on.
- **Disabled** or **Permissive**: your car doesn't need this option. Please still
  send the file (see the end), and stop here.

## 2. Install the test build

```
./hmgapi-adb.sh install
```

Then restart the head unit:

```
adb reboot
```

Wait for the home screen (a few minutes the first time). **If Android doesn't come
back** (logo or black screen for more than 5 minutes), go to
[If Android doesn't start](#if-android-doesnt-start).

## 3. Option off (the default)

```
./hmgapi-adb.sh selinux
```

Expected: `mode: Enforcing`, `option: off`.

Then make a Car Report: in the HMG-API app, open **Car Report** and make one (no USB
stick needed). Copy it to the laptop:

```
adb pull /data/hmgapi/public/reports/
```

## 4. Option on

In the HMG-API app: **Settings → System → Set SELinux to Permissive at start**. Restart:

```
adb reboot
```

Once the home screen is back:

```
./hmgapi-adb.sh selinux
```

Expected: `mode: Permissive`, `at start: enforcing permissive wrapper` (or
`... boot.sh`). Make a second Car Report and pull it the same way. Check that HMG-API's tile and your mods work.

## 5. NEMESIS's switch (optional)

In NEMESIS Manager's Settings, turn **Disable SELinux** off. That sets Enforcing
until the next restart, and HMG-API leaves it that way. Restart: Permissive again.
Run `./hmgapi-adb.sh selinux` after each step.

## 6. Option off again

Turn the switch in **Settings → System** off and restart. Expected:
`mode: Enforcing` (stock). Run `./hmgapi-adb.sh selinux` once more.

## 7. Uninstall (optional)

```
./hmgapi-adb.sh uninstall
```

```
adb reboot
```

Then `./hmgapi-adb.sh selinux` should show `mode: Enforcing` and `app_process` with the
same label as in step 1.

## If Android doesn't start

With the cable in and USB ADB on, run:

```
./hmgapi-adb.sh rescue
```

```
adb reboot
```

This puts back only the stock `app_process`, the one file that starts Android, and
turns HMG-API's hooks off. Once the home screen is back, uninstall
(`./hmgapi-adb.sh uninstall`) and send us what happened.

If `rescue` says `no root on this unit` or can't remount, NEMESIS's daemon didn't
start. That's a known NEMESIS issue after a restart. Run:

```
adb shell "nemesis root -c 'rm /data/nemesis/watchdog.pid'"
```

Then restart (`adb reboot`), wait two minutes and run `./hmgapi-adb.sh rescue` again.

## What to send

Post on the test issue:

- every `selinux-check-<date>.txt` file;
- the Car Reports from steps 3 and 4 (the `reports` folder `adb pull` made);
- what you saw at each step, especially anything that didn't match "Expected".

None of these files hold your VIN, serial numbers, phone numbers or account details.
The checks read only SELinux's mode, file labels and the kernel's SELinux messages,
with MAC addresses removed.
