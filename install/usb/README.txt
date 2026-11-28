HMG-API @VERSION@ - USB stick
============================

Copy everything in this folder to the top level of an empty USB stick (FAT32).
Then pick one of these. The head unit restarts when it's done.

Put the car in P first and keep it there until the head unit has restarted.
Shifting into reverse while an install runs can cut it off half done. HMG-API
refuses to start when it can tell the car isn't in P.

A. The head unit has nothing installed yet (no NEMESIS):
   1. Plug the stick into the car's USB port.
   2. Open Engineering Mode > USB Copy > DSP > Update OTA.
   3. Messages show "HMG-API ... installing", then "HMG-API installed, restarting".
      The OTA screen then shows error 684. That's expected: nothing is undone.

B. NEMESIS is installed: plug the stick in once Android is up and confirm the
   install of "HMG-API".

C. HMG-API is installed: the HMG-API app offers the update from HMGAPI/updates.
   That folder also holds the mods (install them from the app).

Uninstall with the stick (route A): add an empty file named UNINSTALL to the
HMGAPI folder (UNINSTALL-PURGE also deletes HMG-API's data: mods, settings,
backups). Every change HMG-API or its mods made goes back to stock.

The result is written to HMGAPI/install.log on the stick.

Take the stick out afterwards: with it in, the head unit can try to start
wired Android Auto, and NEMESIS offers the install again.

Only for the cars and firmware HMG-API supports (see the compatibility page).
On anything else HMG-API stays off and says why.
