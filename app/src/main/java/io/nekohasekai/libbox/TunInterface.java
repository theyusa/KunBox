package io.nekohasekai.libbox;

/**
 * Compatibility interface to satisfy JNI lookups in Libbox._init.
 * Mirrors the current TunInterface signature in core.
 */
public interface TunInterface {
    int fileDescriptor();

    void close();

    int FileDescriptor();

    void Close();
}