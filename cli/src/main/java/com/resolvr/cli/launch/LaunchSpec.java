package com.resolvr.cli.launch;

import java.nio.file.Path;
import java.util.List;

/** Everything ServerProcessManager needs to spawn a server subprocess, without it needing
 * to know whether that's the packaged jar or `mvnw quarkus:dev`. */
public interface LaunchSpec {

    /** The full command line, e.g. ["java", "-jar", ".../quarkus-run.jar"]. */
    List<String> command();

    Path workingDirectory();

    /** Short human-readable tag stored in the PID file and shown in status/doctor output —
     * e.g. "packaged-jar" or "quarkus-dev". Not used for process identification, just display. */
    String marker();

    /** One-line description for CLI output, e.g. "packaged server jar". */
    String description();
}
