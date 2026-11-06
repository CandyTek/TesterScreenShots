package com.rc.testershot.capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Something that can run a privileged (root or adb-shell uid) command and return its stdout. */
public interface Shell {
    /** Runs a command and returns raw stdout (binary safe). */
    byte[] exec(String command, long timeoutMs) throws IOException;

    /** Short name for logs / metadata: "root" or "adb". */
    String name();

    default String execText(String command, long timeoutMs) throws IOException {
        return new String(exec(command, timeoutMs), StandardCharsets.UTF_8);
    }
}
