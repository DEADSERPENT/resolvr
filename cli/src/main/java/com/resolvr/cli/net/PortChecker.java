package com.resolvr.cli.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * Checks TCP port availability via a real bind attempt (java.net.ServerSocket) — no
 * netstat/lsof/ss shell-outs, so this behaves identically on every OS.
 */
public final class PortChecker {

    private PortChecker() {
    }

    /** True if the port is already bound by something (i.e. NOT available for Resolvr to use). */
    public static boolean isInUse(int port) {
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            socket.setReuseAddress(true);
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
