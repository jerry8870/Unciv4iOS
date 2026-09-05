#!/usr/bin/env bash

set -euo pipefail

readonly BUNDLE_ID="com.aishuati.unciv"

if [[ "$#" -ne 2 ]]; then
    echo "Usage: $(basename "$0") SIMULATOR_UDID /path/to/UncivIOSPOC.app" >&2
    exit 2
fi

simulator_udid="$1"
candidate_app="${2%/}"
candidate_plist="$candidate_app/Info.plist"

[[ -f "$candidate_plist" ]] || {
    echo "Candidate Info.plist is missing: $candidate_plist" >&2
    exit 2
}

candidate_bundle_id="$(/usr/bin/plutil -extract CFBundleIdentifier raw -o - "$candidate_plist")"
[[ "$candidate_bundle_id" == "$BUNDLE_ID" ]] || {
    echo "Candidate bundle identifier is '$candidate_bundle_id', expected '$BUNDLE_ID'" >&2
    exit 1
}

installed_app="$(/usr/bin/xcrun simctl get_app_container "$simulator_udid" "$BUNDLE_ID" app)"
installed_plist="$installed_app/Info.plist"
executable_name="$(/usr/bin/plutil -extract CFBundleExecutable raw -o - "$candidate_plist")"

[[ -f "$candidate_app/$executable_name" && -f "$installed_app/$executable_name" ]] || {
    echo "Candidate or installed executable is missing: $executable_name" >&2
    exit 2
}

installed_bundle_id="$(/usr/bin/plutil -extract CFBundleIdentifier raw -o - "$installed_plist")"
[[ "$installed_bundle_id" == "$BUNDLE_ID" ]] || {
    echo "Installed bundle identifier is '$installed_bundle_id', expected '$BUNDLE_ID'" >&2
    exit 1
}

if ! /usr/bin/cmp -s "$candidate_app/$executable_name" "$installed_app/$executable_name"; then
    echo "Installed executable does not match the current RoboVM simulator build" >&2
    exit 1
fi

echo "PASS: installed $BUNDLE_ID executable matches $candidate_app/$executable_name"
