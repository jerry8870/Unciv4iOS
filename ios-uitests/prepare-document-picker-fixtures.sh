#!/usr/bin/env bash

set -euo pipefail

if [[ "$#" -ne 1 ]]; then
    echo "Usage: $(basename "$0") SIMULATOR_UDID" >&2
    exit 2
fi

readonly simulator_udid="$1"
readonly script_directory="$(cd "$(dirname "$0")" && pwd)"
readonly fixtures_directory="$script_directory/Fixtures"

files_group="$(
    /usr/bin/xcrun simctl get_app_container "$simulator_udid" com.apple.DocumentsApp groups |
        /usr/bin/awk -F $'\t' '$1 == "group.com.apple.FileProvider.LocalStorage" { print $2 }'
)"

if [[ -z "$files_group" ]]; then
    echo "Could not resolve the simulator's local Files provider container" >&2
    exit 1
fi

readonly provider_root="$files_group/File Provider Storage"
/bin/mkdir -p "$provider_root"

for fixture in \
    UncivUITests-invalid-save.txt \
    UncivUITests-valid-4.21.14.txt
do
    [[ -f "$fixtures_directory/$fixture" ]] || {
        echo "Missing fixture: $fixtures_directory/$fixture" >&2
        exit 1
    }
    /bin/cp "$fixtures_directory/$fixture" "$provider_root/$fixture"
done

# Files may keep a stale directory snapshot while its UI process remains alive.
# Restarting only that UI process is safe; the provider contents stay intact.
/usr/bin/xcrun simctl terminate "$simulator_udid" com.apple.DocumentsApp \
    >/dev/null 2>&1 || true

echo "PASS: installed document-picker fixtures in $provider_root"
