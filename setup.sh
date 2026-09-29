#!/usr/bin/env bash
#
# YOU MAY NEED TO RUN chmod +x gradlew first.
#
# Sets up an RS3OS server from a fresh clone: build, keys, client, cache, database.
#
# Runs every setup step that has not already been done, in order, and stops at
# the first failure with an explanation. Safe to run again -- finished steps are
# detected and skipped, so an interrupted download is resumed by re-running.
#
# Steps, in order:
#   check     Java 21 and Python 3 are present
#   build     compile the server            (./gradlew installDist)
#   config    data/config/server.toml       (hostname, build, ports)
#   keys      data/config/rsa.toml          (run-tool rsa-key-generator)
#   client    data/clients/950/             (run-tool client-downloader)
#   patch     the client trusts your key    (run-tool client-patcher)
#   cache     data/cache/                   (run-tool cache-downloader)
#   database  data/rs3.sqlite               (run-tool db-builder)
#   world     collision and placements      (run-tool map-builder)
#   seed      data/seed/npc_cache.json      (tools/seed_from_cache.py)
#
#   ./setup.sh                     # everything, skipping what is already done
#   ./setup.sh --step cache        # just resume the cache download
#   ./setup.sh --cache /mnt/cache  # use a cache you already have
#   ./setup.sh --force             # redo every step
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

BUILD=950
BINARY_TYPE=win64c
RS3OS_BIN="$ROOT/build/install/rs3os/bin/rs3os"

# --------------------------------------------------------------------------
# output helpers
# --------------------------------------------------------------------------
STEP_NO=0
step()  { STEP_NO=$((STEP_NO + 1)); printf '\n\033[36m[%d] %s\033[0m\n' "$STEP_NO" "$1"; }
ok()    { printf '    \033[32mOK\033[0m    %s\n' "$1"; }
skip()  { printf '    \033[90mskip  %s\033[0m\n' "$1"; }
info()  { printf '          \033[90m%s\033[0m\n' "$1"; }
fail()  { printf '\n\033[31mFAILED: %s\033[0m\n' "$1" >&2; exit 1; }

should_run() { [ -z "$ONLY_STEP" ] || [ "$ONLY_STEP" = "$1" ]; }

# The generated rs3os launcher word-splits RS3OS_OPTS, so keep each value free
# of spaces (a cache path containing a space is not supported).
set_tool_opts() {
    RS3OS_OPTS="-Dopennxt.prot.experimentalBuild=$BUILD"
    if [ -n "$CACHE_PATH" ]; then
        RS3OS_OPTS="$RS3OS_OPTS -Dopennxt.cache=$CACHE_PATH"
    fi
    export RS3OS_OPTS
}

rs3os() {
    [ -x "$RS3OS_BIN" ] || fail "the server is not built yet. Run: ./setup.sh --step build"
    info "run-tool: $*"
    set_tool_opts
    "$RS3OS_BIN" "$@" || fail "the server tool failed (exit code $?): $*"
}

# Python 3, preferring the python3 name Linux uses.
PYTHON=""
for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1; then PYTHON="$c"; break; fi
done

usage() {
    cat <<'EOF'
Usage: ./setup.sh [options]

Options:
  --hostname ADDR   Address your client connects to (default 127.0.0.1).
  --cache PATH      Use a game cache that already exists here instead of
                    downloading one into data/cache.
  --step NAME       Run one step only: check build config keys client patch
                    cache database world seed
  --force           Redo steps whose output already exists.
  -h, --help        This text.
EOF
}

# --------------------------------------------------------------------------
# option parsing
# --------------------------------------------------------------------------
HOSTNAME_OPT="127.0.0.1"
CACHE=""
CACHE_PATH=""
ONLY_STEP=""
FORCE=0

while [ $# -gt 0 ]; do
    case "$1" in
        --hostname) HOSTNAME_OPT="$2"; shift 2 ;;
        --cache)    CACHE="$2"; shift 2 ;;
        --step)     ONLY_STEP="$2"; shift 2 ;;
        --force)    FORCE=1; shift ;;
        -h|--help)  usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; usage; exit 2 ;;
    esac
done

case "$ONLY_STEP" in
    ""|check|build|config|keys|client|patch|cache|database|world|seed) ;;
    *) echo "unknown step: $ONLY_STEP" >&2; usage; exit 2 ;;
esac

if [ -n "$CACHE" ]; then
    [ -d "$CACHE" ] || fail "no such cache directory: $CACHE"
    CACHE_PATH="$(cd "$CACHE" && pwd)"
fi

# --------------------------------------------------------------------------
# 1. prerequisites
# --------------------------------------------------------------------------
if should_run check; then
    step "Checking prerequisites"

    command -v java >/dev/null 2>&1 || fail "Java is not on your PATH.

Install a JDK 21 (Temurin is the usual choice) and reopen this shell:
    https://adoptium.net/temurin/releases/?version=21"

    # `java -version` prints to stderr.
    ver_line="$(java -version 2>&1 | head -n1)"
    major="$(printf '%s\n' "$ver_line" | sed -n 's/^[^"]*"\([0-9]\{1,\}\).*/\1/p')"
    if [ -n "$major" ]; then
        if [ "$major" -ne 21 ]; then
            fail "Java $major found, but this project builds with Java 21 specifically.

  $ver_line

Newer versions do not work: the Kotlin compiler aborts with an internal error
that does not say why. Install Java 21 and make it the one on your PATH:

    https://adoptium.net/temurin/releases/?version=21

It can live alongside the Java you already have."
        fi
        ok "Java $major"
    else
        info "could not parse the Java version from: $ver_line"
        info "continuing anyway"
    fi

    if [ -n "$PYTHON" ]; then
        ok "Python 3"
    else
        fail "Python 3 is not on your PATH.

It is needed to extract NPC data during setup and to launch the client.
Install it with your package manager (python3) or from https://www.python.org/downloads/."
    fi
fi

# --------------------------------------------------------------------------
# 2. build
# --------------------------------------------------------------------------
if should_run build; then
    step "Building the server"

    if [ -x "$RS3OS_BIN" ] && [ "$FORCE" -eq 0 ]; then
        skip "already built (pass --force to rebuild)"
    else
        info "this takes a few minutes the first time"
        ./gradlew installDist --console=plain -q || fail "the build failed"
        ok "built to build/install/rs3os/"
    fi
fi

# --------------------------------------------------------------------------
# 3. configuration
# --------------------------------------------------------------------------
if should_run config; then
    step "Writing data/config/server.toml"

    cfg="data/config/server.toml"
    if [ -f "$cfg" ] && [ "$FORCE" -eq 0 ]; then
        skip "server.toml already exists (pass --force to overwrite)"
    else
        cat > "$cfg" <<EOF
# Where your client will look for this server. 127.0.0.1 means this machine.
hostname = "$HOSTNAME_OPT"

# The NXT build this server speaks. The cache and the client must match it.
build = $BUILD

configUrl = "http://$HOSTNAME_OPT/jav_config.ws?binaryType=2"

[networking.ports]
game = 43594
http = 80
https = 443
EOF
        ok "hostname = $HOSTNAME_OPT, build = $BUILD"
    fi

    mods="data/config/mods.json"
    if [ ! -f "$mods" ]; then
        cp data/config/mods.example.json "$mods"
        ok "mods.json created - edit it to give your account admin rights"
    fi
fi

# --------------------------------------------------------------------------
# 4. RSA keys
# --------------------------------------------------------------------------
if should_run keys; then
    step "Generating your RSA key pair"

    if [ -f data/config/rsa.toml ] && [ "$FORCE" -eq 0 ]; then
        skip "rsa.toml already exists"
        info "regenerating it would invalidate the client you have already patched"
    else
        rs3os run-tool rsa-key-generator
        ok "data/config/rsa.toml written"
        info "this is a PRIVATE KEY. It is gitignored. Never publish it."
    fi
fi

# --------------------------------------------------------------------------
# 5. the client
# --------------------------------------------------------------------------
if should_run client; then
    step "Downloading the game client"

    orig="data/clients/$BUILD/$BINARY_TYPE/original"
    if [ -f "$orig/rs2client.exe" ] && [ "$FORCE" -eq 0 ]; then
        skip "the client is already downloaded"
    else
        rs3os run-tool client-downloader

        # The downloader files its result under the build Jagex is serving right
        # now. Say so plainly if that is not the build this server speaks.
        if [ ! -f "$orig/rs2client.exe" ]; then
            others=""
            for d in data/clients/*/; do
                [ -d "$d" ] || continue
                name="$(basename "$d")"
                [ "$name" = "$BUILD" ] || others="$others $name"
            done
            others="${others# }"
            downloaded="${others:-nothing}"
            fail "The downloader fetched build $downloaded, but this server speaks build $BUILD.

It always fetches whatever Jagex is serving today, and there is no way to ask
it for an older build. Once the live game moves past $BUILD, a build $BUILD
client has to come from a copy you already have, placed (with its DLLs and its
jav_config.ws) in:

    data/clients/$BUILD/$BINARY_TYPE/original/"
        fi
        ok "client downloaded to data/clients/$BUILD/$BINARY_TYPE/original"
    fi
fi

# --------------------------------------------------------------------------
# 6. patch the client
# --------------------------------------------------------------------------
if should_run patch; then
    step "Patching the client to trust your key"

    patched="data/clients/$BUILD/$BINARY_TYPE/patched/rs2client.exe"
    if [ -f "$patched" ] && [ "$FORCE" -eq 0 ]; then
        skip "a patched client already exists (pass --force to redo)"
    else
        rs3os run-tool client-patcher
        ok "the client now trusts your RSA key and points at your server"
    fi
fi

# --------------------------------------------------------------------------
# 7. the cache
# --------------------------------------------------------------------------
if should_run cache; then
    cache_dir="data/cache"
    if [ -n "$CACHE_PATH" ]; then
        skip "using the existing cache at $CACHE_PATH"
    elif [ "$FORCE" -eq 0 ] && [ -d "$cache_dir" ] && [ -n "$(find "$cache_dir" -maxdepth 1 -type f -print -quit 2>/dev/null)" ]; then
        n="$(find "$cache_dir" -maxdepth 1 -type f 2>/dev/null | wc -l)"
        skip "data/cache already holds $n file(s)"
        info "run this step again at any time to top it up: ./setup.sh --step cache"
    else
        info "This is tens of gigabytes and will take a long time."
        info "It resumes where it left off, so interrupting it is safe."
        rs3os run-tool cache-downloader
        ok "cache downloaded"
    fi
fi

# --------------------------------------------------------------------------
# 8. the definition database
# --------------------------------------------------------------------------
if should_run database; then
    step "Building data/rs3.sqlite from the cache"

    db="data/rs3.sqlite"
    if [ -f "$db" ] && [ "$FORCE" -eq 0 ]; then
        skip "rs3.sqlite already exists (pass --force to rebuild)"
    else
        # Build to a temporary name and rename on success, so an interrupted or
        # failed build is never mistaken for a finished one on the next run.
        tmp="data/rs3.building.sqlite"
        rm -f "$tmp" "$tmp"-* "$db" "$db"-* 2>/dev/null || true
        rs3os run-tool db-builder --output data/rs3.building.sqlite --force
        mv "$tmp" "$db"
        ok "rs3.sqlite built"
    fi
fi

# --------------------------------------------------------------------------
# 9. the world: collision and object placements
# --------------------------------------------------------------------------
if should_run world; then
    step "Decoding the world map from the cache"

    [ -f data/rs3.sqlite ] || \
        fail "data/rs3.sqlite does not exist yet. Run: ./setup.sh --step database"

    # map-builder refuses a populated set unless forced, so let it make that
    # call rather than second-guessing it from a row count here.
    info "terrain, collision and object placements - a few minutes"
    set_tool_opts
    if [ "$FORCE" -eq 1 ]; then
        "$RS3OS_BIN" run-tool map-builder --database data/rs3.sqlite --force \
            || fail "building the world map failed"
    elif "$RS3OS_BIN" run-tool map-builder --database data/rs3.sqlite; then
        ok "collision and placements written"
    else
        skip "the map tables already hold data (pass --force to rebuild them)"
    fi
fi

# --------------------------------------------------------------------------
# 10. NPC combat seed, from your own cache
# --------------------------------------------------------------------------
if should_run seed; then
    step "Extracting NPC combat data from your database"

    if [ -f data/seed/npc_cache.json ] && [ "$FORCE" -eq 0 ]; then
        skip "npc_cache.json already exists (pass --force to rebuild)"
    else
        [ -n "$PYTHON" ] || fail "Python 3 is needed for this step but was not found on your PATH."
        "$PYTHON" tools/seed_from_cache.py || fail "extracting the NPC seed failed"
        ok "data/seed/npc_cache.json written from your own cache"
    fi
fi

# --------------------------------------------------------------------------
# done
# --------------------------------------------------------------------------
if [ -z "$ONLY_STEP" ]; then
    cat <<EOF

$(printf '\033[32mSetup complete.\033[0m')

  Start the server and the client:
      ./run.sh

  Give yourself admin rights by putting your account name in
      data/config/mods.json

EOF
fi
