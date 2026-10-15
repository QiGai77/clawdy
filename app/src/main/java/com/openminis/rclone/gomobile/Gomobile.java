package com.openminis.rclone.gomobile;

/** Clawdy: stub for the gomobile rclone binding. Remote backup is disabled in this build. */
public final class Gomobile {
    public static final class RcloneRPCResult {
        public String output = "{\"error\":\"rclone is not bundled in this build\"}";
        public long status = 501L;
        public String getOutput() { return output; }
        public long getStatus() { return status; }
    }
    public static void rcloneInitialize() {}
    public static RcloneRPCResult rcloneRPC(String method, String input) { return new RcloneRPCResult(); }
}
