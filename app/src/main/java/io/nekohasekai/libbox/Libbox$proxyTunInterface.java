package io.nekohasekai.libbox;

/**
 * Compatibility class to satisfy JNI lookups in Libbox._init.
 * Mirrors the current TunInterface signature in core and provides the
 * ctor(int).
 */
public class Libbox$proxyTunInterface {
    private final int fd;

    public Libbox$proxyTunInterface(int fd) {
        this.fd = fd;
    }

    public int FileDescriptor() throws Exception {
        return fd;
    }

    public void Close() throws Exception {
        // no-op: close is handled by native side
    }
}