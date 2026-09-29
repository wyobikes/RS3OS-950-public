#!/usr/bin/env bash
#
#
# Starts the RS3OS server in the foreground, streaming its log to this terminal
# and saving a copy to logs/.
#
# The NXT client is a Windows executable, so on Linux this runs the server only;
# launch the patched client from Windows (or Wine) against it.
#
# Most gameplay lives behind an -Dopennxt.experiment.* switch so that a
# half-finished system can be turned off without touching code; the defaults
# below turn on everything that works.
#
#   ./run.sh
#   ./run.sh --cache /mnt/rs3-cache --flags '-Dopennxt.experiment.combat.realdamage=false'
#   ./run.sh --client-only       # launch a client against an already-running server
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

BUILD=950
BINARY_TYPE=win64c

CACHE=""
FLAGS=""
MEMORY="4g"
SERVER_ONLY=0
CLIENT_ONLY=0

usage() {
    cat <<'EOF'
Usage: ./run.sh [options]

Options:
  --cache PATH      Where the game cache lives (default data/cache).
  --flags FLAGS     Extra -D switches appended after the defaults (space separated).
  --memory SIZE     JVM maximum heap (default 4g).
  --server-only     Run the server only (the Linux default).
  --client-only     Launch a client against a server that is already running.
  -h, --help        This text.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --cache)       CACHE="$2"; shift 2 ;;
        --flags)       FLAGS="$2"; shift 2 ;;
        --memory)      MEMORY="$2"; shift 2 ;;
        --server-only) SERVER_ONLY=1; shift ;;
        --client-only) CLIENT_ONLY=1; shift ;;
        -h|--help)     usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; usage; exit 2 ;;
    esac
done

fail() { printf '\n\033[33m%s\033[0m\n' "$1" >&2; exit 1; }
info() { printf '\033[36m%s\033[0m\n' "$1"; }
ok()   { printf '\033[32m%s\033[0m\n' "$1"; }

# Python 3 (needed only to launch the client).
PYTHON=""
for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1; then PYTHON="$c"; break; fi
done

is_windows_host() {
    case "$(uname -s 2>/dev/null)" in
        MINGW*|MSYS*|CYGWIN*) return 0 ;;
        *) return 1 ;;
    esac
}

launch_client() {
    local client="$ROOT/data/clients/$BUILD/$BINARY_TYPE/patched/rs2client.exe"
    [ -f "$client" ] || fail "No patched client yet - run ./setup.sh first."
    if ! is_windows_host; then
        fail "the NXT client is Windows-only and cannot be launched from this host."
    fi
    [ -n "$PYTHON" ] || fail "Python 3 is needed to launch the client but was not found on your PATH."
    info "Launching the client. Log in with any username and password;"
    info "a new account is created the first time you use a name."
    "$PYTHON" tools/launch_client.py
}

port_in_use() {
    if command -v ss >/dev/null 2>&1; then
        if ss -ltn 2>/dev/null | awk 'NR>1 {print $4}' | grep -Eq '[:.]43594$'; then
            return 0
        fi
        return 1
    fi
    if command -v netstat >/dev/null 2>&1; then
        if netstat -ltn 2>/dev/null | awk 'NR>2 {print $4}' | grep -Eq '[:.]43594$'; then
            return 0
        fi
        return 1
    fi
    return 1
}

if [ "$CLIENT_ONLY" -eq 1 ]; then
    launch_client
    exit 0
fi

LIB="$ROOT/build/install/rs3os/lib"
if [ ! -d "$LIB" ]; then
    fail "The server is not built yet. Run ./setup.sh first."
fi

[ -n "$CACHE" ] || CACHE="$ROOT/data/cache"
if [ ! -d "$CACHE" ]; then
    fail "No cache at $CACHE - run ./setup.sh first, or pass --cache <path>."
fi

for f in rsa.toml server.toml; do
    [ -f "$ROOT/data/config/$f" ] || fail "Missing data/config/$f - run ./setup.sh first."
done
[ -f "$ROOT/data/rs3.sqlite" ] || fail "Missing data/rs3.sqlite - run ./setup.sh first."

if port_in_use; then
    fail "Port 43594 is already in use. Is a server already running?"
fi

# Protocol and world.
#   prot.experimentalBuild=950   speak the build 950 protocol tables in data/prot/950
#   npc.dispatch                 route clicks on NPCs into content (talk, fish, attack)
#   banks.ui                     the bank window, deposits and withdrawals
#   equip                        wielding and removing equipment
world=(
    "-Dopennxt.prot.experimentalBuild=$BUILD"
    '-Dopennxt.experiment.npc.dispatch=true'
    '-Dopennxt.experiment.banks.ui=true'
    '-Dopennxt.experiment.equip=true'
)

# Combat.
#   combat              hit splats, damage, death, drops, combat xp
#   combat.realdamage   damage rolled from your stats and weapon instead of a flat value
#   abilityQueue        abilities queue behind the global cooldown
#   movementAbilities   Surge, Escape and similar
combat=(
    '-Dopennxt.experiment.combat=true'
    '-Dopennxt.experiment.combat.realdamage=true'
    '-Dopennxt.combat.abilityQueue=on'
    '-Dopennxt.combat.movementAbilities=on'
)

# Interface.
#   sendStats             push skill levels and xp to the skills panel, shortly after login
#   ui.varcReplay=false   do not replay stored interface variables at login
#   varpSmallAsLarge=off  send each player variable with its own packet size
interface=(
    '-Dopennxt.experiment.sendStats=true'
    '-Dopennxt.experiment.sendStats.delayTicks=20'
    '-Dopennxt.experiment.ui.varcReplay=false'
    '-Dopennxt.compat.varpSmallAsLarge=off'
)

# An optional table of player variables to send at login, one "id<TAB>value"
# per line. Not shipped; see the README.
varps="$ROOT/data/config/login-varps-$BUILD.tsv"
if [ -f "$varps" ]; then
    interface+=("-Dopennxt.experiment.varpFile=$varps")
fi

jvm_args=(
    "-Xmx$MEMORY"
    "-Dopennxt.cache=$CACHE"
    # Route the slf4j log to stderr: stderr is line-flushed, so it streams
    # through the pipe below without buffering delays.
    '-Dorg.slf4j.simpleLogger.logFile=System.err'
    "${world[@]}"
    "${combat[@]}"
    "${interface[@]}"
)
if [ -n "$FLAGS" ]; then
    read -r -a extra <<< "$FLAGS"
    if [ "${#extra[@]}" -gt 0 ]; then
        jvm_args+=( "${extra[@]}" )
    fi
fi

mkdir -p logs
stamp="$(date +%Y%m%d-%H%M%S)"
out="$ROOT/logs/server-$stamp.log"

info "Starting the server (cache: $CACHE)"
info "  live log: this terminal"
info "  saved:    $out"

# Run in the foreground so the log streams here (like the older Linux run.sh).
# tee also writes a copy to logs/ for later review.
java "${jvm_args[@]}" -cp "$LIB/*" com.opennxt.MainKt run-server 2>&1 | tee "$out"
