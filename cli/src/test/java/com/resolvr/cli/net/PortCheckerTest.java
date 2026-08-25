package com.resolvr.cli.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

class PortCheckerTest {

    @Test
    void reportsInUse_whileSocketIsBound() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = socket.getLocalPort();
            assertTrue(PortChecker.isInUse(port));
        }
    }

    @Test
    void reportsAvailable_afterSocketIsClosed() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        assertFalse(PortChecker.isInUse(port));
    }
}
