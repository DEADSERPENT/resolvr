package com.resolvr.cli.testutil;

import com.resolvr.cli.launch.LaunchSpec;

import java.nio.file.Path;
import java.util.List;

public record FixedLaunchSpec(List<String> command, Path workingDirectory, String marker,
                               String description) implements LaunchSpec {
}
