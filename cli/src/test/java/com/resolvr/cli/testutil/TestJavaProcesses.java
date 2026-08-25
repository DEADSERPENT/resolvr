package com.resolvr.cli.testutil;

import java.nio.file.Path;
import java.util.List;

/** Builds a `java -cp <this test's classpath> <MainClass> [args]` command list — used to
 * spawn real, fast, cross-platform-guaranteed-present test fixture processes (FakeServerMain,
 * or a deliberately-missing main class to simulate a fast failure) via the exact `java`
 * that's running the tests, so no external tool or built server artifact is required. */
public final class TestJavaProcesses {

    private TestJavaProcesses() {
    }

    public static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        return Path.of(javaHome, "bin", windows ? "java.exe" : "java").toString();
    }

    public static List<String> command(String mainClass, String... args) {
        String classpath = System.getProperty("java.class.path");
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaExecutable());
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(mainClass);
        cmd.addAll(List.of(args));
        return List.copyOf(cmd);
    }

    /** A command that reliably fails fast: no such main class on the classpath. */
    public static List<String> failFastCommand() {
        return command("com.resolvr.cli.testutil.ThisClassDoesNotExist");
    }
}
