# source me. JDK/SDK from /workspace/tools (see setup-host-aarch64.sh).
export JAVA_HOME=/workspace/tools/jdk17 PATH=/workspace/tools/jdk17/bin:$PATH ANDROID_HOME=/workspace/tools/sdk ANDROID_SDK_ROOT=/workspace/tools/sdk
# Hark sandbox only: the egress proxy needs auth, which Gradle/sdkmanager cannot do over CONNECT;
# fwdproxy.py is a local unauthenticated forwarder on 127.0.0.1:3128 that adds it. Delete elsewhere.
if [ -n "$HTTPS_PROXY" ]; then
  (echo > /dev/tcp/127.0.0.1/3128) 2>/dev/null || { (setsid python3 /workspace/tools/fwdproxy.py >/dev/null 2>&1 &); sleep 1; }
  export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=3128 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=3128 -Dhttp.nonProxyHosts=localhost"
fi
command -v qemu-x86_64 >/dev/null 2>&1 || { sudo apt-get update -qq; sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq qemu-user libc6-amd64-cross libgcc-s1-amd64-cross libstdc++6-amd64-cross; } >/dev/null 2>&1
