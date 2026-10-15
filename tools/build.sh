#!/bin/bash
# Full debug build; log in /workspace/tools/build.log. Run it detached (setsid) — it takes 10+ min.
source /workspace/tools/env.sh
cd "$(dirname "$0")/.."
[ -f local.properties ] || echo "sdk.dir=/workspace/tools/sdk" > local.properties
./gradlew --no-daemon :app:assembleDebug -x lint "$@" > /workspace/tools/build.log 2>&1
echo "EXIT $?" >> /workspace/tools/build.log
grep -E "^e:|error:|FAILED|BUILD|EXIT" /workspace/tools/build.log | grep -v "Picked up" | head -40
