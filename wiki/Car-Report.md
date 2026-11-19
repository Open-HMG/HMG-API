# Car Report

A Car Report is how a car, region or software version gets added to HMG-API, and how
anything missing on your car gets fixed. It takes about a minute.

1. HMG-API app → **Car Report** → **Make report**. (No HMG-API tile on your car?
   Open the app from NEMESIS Manager → My Packages → HMG-API → **Launch**.)
2. Scan the QR code with your phone. It opens a GitHub issue with the summary filled
   in.
3. Get the report file and attach it to the issue, then submit.

## After you send it

Each build (model, region and software version) has one open "Car tracker" issue, and
new reports are sorted into it automatically:

- **Your build has a tracker:** your report's summary goes there and your issue is
  closed with a link to it. Follow up in the tracker. Closing it doesn't mean the
  problem is fixed.
- **No tracker yet:** your issue becomes the tracker for your build.
- **Head unit software older than 2026** (a version below `260000`): HMG-API doesn't
  support it, so the issue is closed with a request to update the head unit first.
  Send a new report once it's updated.

If it was sorted wrong, say so in the issue and a maintainer will reopen it.

## Getting the file

**USB stick:** plug one in before making the report. The file is in the folder
`HMG-API reports` on the stick.

**adb** (a computer with adb, the head unit's adb on): the file name is on the screen.

```
adb pull /data/hmgapi/public/reports/
```

That copies every report HMG-API made into a `reports` folder on your computer.

The USB port works for one or the other: while adb over USB is on, the port is in
computer mode and doesn't read sticks.

## What's in it

- What the head unit is: model, region, software version, platform.
- Your car's hook map, checked against your car.
- Your car mapped from its own software: the names of the places HMG-API hooks, found
  on the spot. Names only, no code.
- Which mods are installed and whether each one loads on your car.
- HMG-API's recent warnings and errors.
- The head unit's network: its firewall (the car's own rules), routes, and the
  connections the firewall blocked. Device addresses (your phone's, the modem's)
  are taken out.

## What's never in it

Your VIN, serial numbers, phone numbers, IMEI, account details, addresses, or anything
from your own data. Log lines are scrubbed before they go in, and you can open the
`.md` file next to the report to read it first.
