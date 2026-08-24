package com.resolvr.workspace;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GitRemoteParserTest {

    @Test
    void parse_sshScpStyle_extractsOwnerAndRepo() {
        var ref = GitRemoteParser.parse("git@github.com:DEADSERPENT/resolvr.git");
        assertTrue(ref.isPresent());
        assertEquals("DEADSERPENT", ref.get().owner());
        assertEquals("resolvr", ref.get().name());
    }

    @Test
    void parse_sshUrlStyle_extractsOwnerAndRepo() {
        var ref = GitRemoteParser.parse("ssh://git@github.com/DEADSERPENT/resolvr.git");
        assertTrue(ref.isPresent());
        assertEquals("DEADSERPENT", ref.get().owner());
        assertEquals("resolvr", ref.get().name());
    }

    @Test
    void parse_httpsStyle_extractsOwnerAndRepo() {
        var ref = GitRemoteParser.parse("https://github.com/DEADSERPENT/resolvr.git");
        assertTrue(ref.isPresent());
        assertEquals("DEADSERPENT", ref.get().owner());
        assertEquals("resolvr", ref.get().name());
    }

    @Test
    void parse_httpsStyle_withoutDotGitSuffix_extractsOwnerAndRepo() {
        var ref = GitRemoteParser.parse("https://github.com/DEADSERPENT/resolvr");
        assertTrue(ref.isPresent());
        assertEquals("DEADSERPENT", ref.get().owner());
        assertEquals("resolvr", ref.get().name());
    }

    @Test
    void parse_httpsStyle_withEmbeddedCredentials_extractsOwnerAndRepo() {
        var ref = GitRemoteParser.parse("https://x-access-token@github.com/DEADSERPENT/resolvr.git");
        assertTrue(ref.isPresent());
        assertEquals("DEADSERPENT", ref.get().owner());
    }

    @Test
    void parse_nonGitHubHost_returnsEmpty() {
        assertTrue(GitRemoteParser.parse("git@gitlab.com:acme/example.git").isEmpty());
        assertTrue(GitRemoteParser.parse("https://bitbucket.org/acme/example.git").isEmpty());
    }

    @Test
    void parse_null_returnsEmpty() {
        assertTrue(GitRemoteParser.parse(null).isEmpty());
    }

    @Test
    void parse_blank_returnsEmpty() {
        assertTrue(GitRemoteParser.parse("  ").isEmpty());
    }

    @Test
    void parse_unrecognizedFormat_returnsEmpty() {
        assertTrue(GitRemoteParser.parse("not a url").isEmpty());
    }

    @Test
    void parseHost_nonGitHubRemote_returnsHostForDiagnostics() {
        Optional<String> host = GitRemoteParser.parseHost("git@gitlab.com:acme/example.git");
        assertEquals(Optional.of("gitlab.com"), host);
    }

    @Test
    void parseHost_unrecognizedFormat_returnsEmpty() {
        assertTrue(GitRemoteParser.parseHost("not a url").isEmpty());
    }
}
