#!/bin/sh
# Off-car test of the vehicle commands' guards (vehicle/Commands) against a fake car,
# of what the MICOM watch never writes (vehicle/Scrub), and of the outside
# temperature's -40 rules (vehicle/Ambient).
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d)
trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/vehicle/Commands.java core/runtime/java/hmgapi/vehicle/Scrub.java core/runtime/java/hmgapi/vehicle/Ambient.java core/runtime/test/hmgapi/vehicle/CommandsTest.java core/runtime/test/hmgapi/vehicle/AmbientTest.java
java -cp "$CP" hmgapi.vehicle.CommandsTest
java -cp "$CP" hmgapi.vehicle.AmbientTest
