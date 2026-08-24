package com.resolvr.workspace;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a git remote URL (SSH scp-like, ssh://, or https://) into a host +
 * owner/repo triple, so workspace → PR discovery can tell whether "origin"
 * actually points at github.com before ever calling the GitHub API.
 */
public final class GitRemoteParser {

    public record GitHubRepoRef(String owner, String name) {
    }

    private static final Pattern SSH_SCP = Pattern.compile(
            "^git@(?<host>[^:]+):(?<owner>[^/]+)/(?<repo>.+?)(?:\\.git)?/?$");
    private static final Pattern SSH_URL = Pattern.compile(
            "^ssh://git@(?<host>[^/]+)/(?<owner>[^/]+)/(?<repo>.+?)(?:\\.git)?/?$");
    private static final Pattern HTTPS_URL = Pattern.compile(
            "^https?://(?:[^@/]+@)?(?<host>[^/]+)/(?<owner>[^/]+)/(?<repo>.+?)(?:\\.git)?/?$");

    private GitRemoteParser() {
    }

    /** Owner/repo, only if the remote's host is github.com. Empty for non-GitHub or unparsable remotes. */
    public static Optional<GitHubRepoRef> parse(String remoteUrl) {
        Matcher m = match(remoteUrl);
        if (m == null || !"github.com".equals(m.group("host").toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(new GitHubRepoRef(m.group("owner"), m.group("repo")));
    }

    /** The host of any recognizable git remote URL, GitHub or not — for clear error messages. */
    public static Optional<String> parseHost(String remoteUrl) {
        Matcher m = match(remoteUrl);
        return m == null ? Optional.empty() : Optional.of(m.group("host").toLowerCase(Locale.ROOT));
    }

    private static Matcher match(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return null;
        }
        String url = remoteUrl.trim();
        Matcher m = SSH_SCP.matcher(url);
        if (m.matches()) return m;
        m = SSH_URL.matcher(url);
        if (m.matches()) return m;
        m = HTTPS_URL.matcher(url);
        if (m.matches()) return m;
        return null;
    }
}
