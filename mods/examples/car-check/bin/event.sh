# Car Check: one line per car event in its log (hmgapid service-log car-check on-<event>).
# $1 is the event; the vehicle service waits for this to end (a few seconds at shutdown).
echo "car event $1 at $(date) (power: $(hmgapid vehicle power), doors closed: $(hmgapid vehicle doors.closed))"
