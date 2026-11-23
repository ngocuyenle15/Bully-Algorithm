#!/usr/bin/env bash
# Mutation test cho cac guard Java trong diff. Chay tu Git Bash o thu muc goc repo:
#   bash scripts/mutation-java.sh            # tat ca mutant
#   bash scripts/mutation-java.sh J4 J5      # chi mot so mutant
# Moi mutant: thay mot doan code (phai tim thay dung 1 lan), build lai, chay test lien quan,
# ky vong test FAIL ("KILLED"). File goc luon duoc khoi phuc (trap), ke ca khi bi ngat.
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER=server-node/src/main/java/vn/bully/server/ServerNode.java
CLIENT=client-node/src/main/java/vn/bully/client/ClientNode.java
WORKER=document-worker/src/main/java/vn/bully/worker/WorkerState.java
BACKUP=$(mktemp -d)
ORIGINAL=$(sha1sum "$SERVER" "$CLIENT" "$WORKER")
# Khoi phuc moi file da sao luu, xoa ban sao, roi KIEM TRA checksum == ban goc (bao ve chinh harness).
restore() {
  local f
  for f in "$BACKUP"/*.orig; do [ -e "$f" ] && cp "$f" "$(cat "${f%.orig}.path")"; done
  rm -f "$BACKUP"/*
  if [ "$(sha1sum "$SERVER" "$CLIENT" "$WORKER")" != "$ORIGINAL" ]; then
    echo "FATAL: source not restored to original" >&2; exit 4
  fi
}
trap 'restore; rm -rf "$BACKUP"' EXIT

# mutate FILE FROM TO : thay dung 1 lan, nguoc lai bao loi (mutant khong ap dung duoc = khong co bang chung)
mutate() {
  local file=$1 key; key=$(echo "$file" | tr '/' '_')
  # Chi sao luu LAN DAU trong mot mutant: sao luu lai sau lan sua thu nhat se ghi de ban goc.
  if [ ! -e "$BACKUP/$key.orig" ]; then cp "$file" "$BACKUP/$key.orig"; echo "$file" > "$BACKUP/$key.path"; fi
  FROM="$2" TO="$3" perl -0pi -e '$n = s/\Q$ENV{FROM}\E/$ENV{TO}/g; END { exit($n == 1 ? 0 : 3) }' "$file" \
    || { echo "NOT-APPLIED: pattern not found exactly once in $file" >&2; return 3; }
}

# run MODULE TESTS [integration] -> 0 neu test pass, 1 neu fail
run() {
  local module=$1 tests=$2 extra=${3:-}
  if [ -n "$extra" ]; then mvn -q -f server-node/pom.xml package -DskipTests >/dev/null 2>&1; fi
  mvn -q -f "$module/pom.xml" test $extra "-Dtest=$tests" -Dsurefire.failIfNoSpecifiedTests=false >/dev/null 2>&1
}

declare -a RESULTS
case_() { # id description module tests integration-flag
  local id=$1 desc=$2 module=$3 tests=$4 integ=${5:-}
  local flag=""; [ -n "$integ" ] && flag="-Dbully.integration=true"
  local line
  if run "$module" "$tests" "$flag"; then line="$id SURVIVED  $desc"; else line="$id KILLED    $desc"; fi
  RESULTS+=("$line"); echo "$line"   # in ngay, khong mat ket qua neu mutant sau bi loi
  restore
}
want() { [ $# -eq 0 ] && return 0; local x; for x in "${SELECTED[@]}"; do [ "$x" = "$1" ] && return 0; done; return 1; }
SELECTED=("$@")
sel() { [ ${#SELECTED[@]} -eq 0 ] || want "$1"; }

if sel J1; then
  mutate $SERVER 'if (!eligible()) {' 'if (!workerHealthy()) {'
  case_ J1 'ELECTION_OK guard uses only Worker health (old)' document-worker 'DistributedIntegrationTest#warmingUp*' I
fi
if sel J2; then
  mutate $SERVER 'if (peer > id && peerEligible) startElection();' 'if (peer > id) startElection();'
  case_ J2 'coordinator ignores eligible flag of higher peer' document-worker 'DistributedIntegrationTest#warmingUp*' I
fi
if sel J3; then
  mutate $SERVER 'prober.scheduleWithFixedDelay(this::probePeers' 'scheduler.scheduleWithFixedDelay(this::probePeers'
  mutate $SERVER 'calls.put(node.id(), connections.submit(() ->' 'calls.put(node.id(), inline(() ->'
  mutate $SERVER '    @Override public void close() {' '    private static <T> Future<T> inline(Callable<T> c) { var f = new FutureTask<T>(c); f.run(); return f; }
    @Override public void close() {'
  case_ J3 'probes sequential + on lease-renewal thread (old)' document-worker 'DistributedIntegrationTest#unresponsive*' I
fi
if sel J3b; then
  mutate $SERVER 'calls.put(node.id(), connections.submit(() ->' 'calls.put(node.id(), inline(() ->'
  mutate $SERVER '    @Override public void close() {' '    private static <T> Future<T> inline(Callable<T> c) { var f = new FutureTask<T>(c); f.run(); return f; }
    @Override public void close() {'
  case_ J3b 'broadcast sequential only (probe still on own thread)' document-worker 'DistributedIntegrationTest#unresponsive*' I
fi
if sel J4; then
  mutate $CLIENT 'if (generation > bestGeneration) {' 'if (best == null) {'
  case_ J4 'client follows first answer, not highest generation' client-node 'ClientNodeTest'
fi
if sel J5; then
  mutate $CLIENT '} catch (ExecutionException | RuntimeException ignored) {' '} catch (ExecutionException ignored) {'
  case_ J5 'client discover lets malformed reply escape' client-node 'ClientNodeTest'
fi
if sel J6; then
  mutate $WORKER 'if (leaseUntil > now) return' 'if (leaseUntil > now && r.getCoordinatorId() != coordinator) return'
  case_ J6 'Worker lets same ID re-claim a live lease' document-worker 'WorkerStateTest'
fi
if sel J7; then
  mutate $SERVER 'if (coordinator && System.nanoTime() >= leaseDeadlineNanos) demote(false);' 'if (coordinator) demote(false);'
  case_ J7 'demote on any tick exception (old)' document-worker 'DistributedIntegrationTest' I
fi
if sel J8; then
  mutate $CLIENT 'catch (RuntimeException e) { log.info("Client tick failed: " + e); }' 'catch (IllegalCallerException e) { log.info("Client tick failed: " + e); }'
  case_ J8 'client tick without exception containment' client-node 'ClientNodeTest'
fi
if sel J9; then
  mutate $SERVER 'else if (coordinatorId == 0 && holdsLease(candidate)) {' 'else if (coordinatorId == 0) {'
  case_ J9 'moved guard: adopt higher coordinator without Worker lease check' document-worker 'DistributedIntegrationTest' I
fi

# build lai jar tu source da khoi phuc de cac test sau dung dung ban
mvn -q -f server-node/pom.xml package -DskipTests >/dev/null 2>&1
printf '%s\n' "${RESULTS[@]}"
