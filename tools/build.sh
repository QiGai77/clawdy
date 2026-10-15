source /workspace/tools/env.sh
cd /workspace/projects/3622ac84-9be2-42fb-afd5-1b7377271b79/src/clawdy
./gradlew --no-daemon :app:assembleDebug -x lint "$@" > /workspace/tools/build.log 2>&1
echo "EXIT $?" >> /workspace/tools/build.log
grep -E "^e:|error:|FAILED|BUILD|EXIT" /workspace/tools/build.log | grep -v "Picked up" | head -40
