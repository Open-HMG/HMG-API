#!/bin/sh
# Off-car test of the vehicle commands' guards (vehicle/Commands) against a fake car,
# of what the MICOM watch never writes (vehicle/Scrub), and of the outside
# temperature's -40 rules (vehicle/Ambient), of when the car counts as parked and how a car
# event runs (vehicle/Parking, vehicle/EventRun), and of the cluster battery-info cache.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d)
trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/vehicle/Commands.java core/runtime/java/hmgapi/vehicle/Scrub.java core/runtime/java/hmgapi/vehicle/Ambient.java core/runtime/java/hmgapi/vehicle/Parking.java core/runtime/java/hmgapi/vehicle/EventRun.java core/runtime/test/hmgapi/vehicle/CommandsTest.java core/runtime/test/hmgapi/vehicle/AmbientTest.java core/runtime/test/hmgapi/vehicle/ParkingTest.java
javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/vehicle/BatteryInfo.java core/runtime/test/hmgapi/vehicle/BatteryInfoTest.java
java -cp "$CP" hmgapi.vehicle.CommandsTest
java -cp "$CP" hmgapi.vehicle.AmbientTest
java -cp "$CP" hmgapi.vehicle.ParkingTest
java -cp "$CP" hmgapi.vehicle.BatteryInfoTest
