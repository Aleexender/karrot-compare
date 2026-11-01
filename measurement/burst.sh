#!/bin/bash
# Alternates apps; 90s gaps let client-side TIME_WAIT ports drain between 10k-socket runs.
# Prerequisite: both apps running; with-design needs users 100001..101000 (see docs/bug-rate-comparison.md).
cnt() { docker exec karrot-mysql mysql -ukarrot -pkarrot -N -B "$1" -e "SELECT COUNT(*) FROM $2" 2>/dev/null; }
run() { # app db table
  local before after tw
  tw=$(netstat -an -p tcp | grep -c TIME_WAIT)
  before=$(cnt "$2" "$3")
  local out; out=$(java -Xss256k "$(dirname "$0")"/Burst.java "$1" 100001 2>&1 | tail -1)
  sleep 2
  after=$(cnt "$2" "$3")
  echo "$1 time_wait_before=$tw db_delta=$((after-before)) $out"
}
for round in 1 2; do
  run with-design karrot_with_design items; sleep 90
  run no-design karrot_no_design posts; if [ "$round" = 1 ]; then sleep 90; fi
done
