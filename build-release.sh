#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -f keystore.properties ]]; then
  echo "Missing keystore.properties. Release signing must be configured before building for Google Play." >&2
  exit 1
fi

echo "Building signed release AAB for Google Play..."
bash ./gradlew :app:bundleRelease

output_dir="app/build/outputs/bundle/release"
mapfile -t bundles < <(find "$output_dir" -maxdepth 1 -type f -name "*.aab" | sort)

echo
echo "========================================"
echo "Build successful!"
echo "========================================"
echo
echo "AAB location:"

if [[ ${#bundles[@]} -eq 0 ]]; then
  echo "No AAB found in $output_dir" >&2
  exit 1
fi

for bundle in "${bundles[@]}"; do
  echo "$(pwd)/$bundle"
done
