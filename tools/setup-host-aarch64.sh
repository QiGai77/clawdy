set -e
cd /workspace/tools
sudo apt-get install -y -qq qemu-user libc6-amd64-cross >/dev/null 2>&1 || sudo apt-get install -y -qq qemu-user
curl -sL -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jdk/hotspot/normal/eclipse"
mkdir -p jdk17 && tar xzf jdk.tgz -C jdk17 --strip-components=1 && rm jdk.tgz
export JAVA_HOME=/workspace/tools/jdk17 PATH=/workspace/tools/jdk17/bin:$PATH
java -version
yes | sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/workspace/tools/sdk --licenses >/dev/null
sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/workspace/tools/sdk "platform-tools" "platforms;android-36" "build-tools;35.0.0" > sdk_install.log 2>&1
curl -sL -o nanoMuse-1.0.5.apk https://github.com/nano-muse/nanoMuse/releases/download/v1.0.5/nanoMuse-1.0.5-arm64.apk
ls -la
echo DONE
