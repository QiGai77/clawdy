#!/bin/bash
# One-time build host setup for an aarch64 Linux box (e.g. the Hark sandbox): JDK 17, Android SDK
# (platform 36, build-tools 35.0.0), qemu for the x86_64-only aapt2. Everything lands in /workspace/tools.
# Re-runnable. Then: ./tools/build.sh  (≈10–13 min for a clean :app:assembleDebug).
set -ex
mkdir -p /workspace/tools && cd /workspace/tools
cp "$(dirname "$0")"/fwdproxy.py "$(dirname "$0")"/env.sh "$(dirname "$0")"/aapt2 /workspace/tools/ 2>/dev/null || true
chmod +x /workspace/tools/aapt2
sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq qemu-user libc6-amd64-cross libgcc-s1-amd64-cross libstdc++6-amd64-cross unzip
if [ ! -x jdk17/bin/java ]; then
  curl -sL -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk17 && tar xzf jdk.tgz -C jdk17 --strip-components=1 && rm jdk.tgz
fi
# Hark sandbox: outbound TLS is re-signed by the gateway CA; Java needs it in its trust store.
if [ -f /opt/certs/gateway-ca.crt ]; then
  jdk17/bin/keytool -importcert -noprompt -alias hark-gateway -file /opt/certs/gateway-ca.crt \
    -keystore jdk17/lib/security/cacerts -storepass changeit || true
fi
if [ ! -x sdk/cmdline-tools/latest/bin/sdkmanager ]; then
  curl -sL -o cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  mkdir -p sdk/cmdline-tools && unzip -q cmdline.zip -d sdk/cmdline-tools && mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest && rm cmdline.zip
fi
source /workspace/tools/env.sh
SM="sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/workspace/tools/sdk"
[ -n "$HTTPS_PROXY" ] && SM="$SM --proxy=http --proxy_host=127.0.0.1 --proxy_port=3128"
yes | $SM --licenses >/dev/null
$SM "platform-tools" "platforms;android-36" "build-tools;35.0.0" > sdk_install.log 2>&1
./aapt2 version
echo DONE
