package com.resolvr.cli.runtime;

/**
 * Reports the JVM currently running this CLI. Since the CLI itself needs a JVM to run at all
 * (dev-mode fallback, per the architecture decision to defer a fully self-contained native/
 * bundled-runtime build), the JVM that launched the CLI is also the one that would run the
 * Resolvr server unless the user has configured a different one — so reporting "the current
 * runtime" here is meaningful, not circular.
 */
public final class JavaRuntimeInfo {

    public static final int MINIMUM_FEATURE_VERSION = 21;

    private final int featureVersion;
    private final String versionString;
    private final String vendor;

    private JavaRuntimeInfo(int featureVersion, String versionString, String vendor) {
        this.featureVersion = featureVersion;
        this.versionString = versionString;
        this.vendor = vendor;
    }

    public static JavaRuntimeInfo detectCurrent() {
        return of(Runtime.version().feature(), Runtime.version().toString(),
                System.getProperty("java.vendor", "unknown"));
    }

    /** Package-visible-equivalent factory for tests — avoids depending on the actual running JVM's version. */
    public static JavaRuntimeInfo of(int featureVersion, String versionString, String vendor) {
        return new JavaRuntimeInfo(featureVersion, versionString, vendor);
    }

    public int featureVersion() {
        return featureVersion;
    }

    public String versionString() {
        return versionString;
    }

    public String vendor() {
        return vendor;
    }

    public boolean meetsMinimum() {
        return featureVersion >= MINIMUM_FEATURE_VERSION;
    }
}
