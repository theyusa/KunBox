package com.kunk.singbox.aidl;

import com.kunk.singbox.aidl.ISingBoxServiceCallback;

interface ISingBoxService {
    int getState();
    String getActiveLabel();
    String getLastError();
    boolean isManuallyStopped();
    void registerCallback(ISingBoxServiceCallback callback);
    void unregisterCallback(ISingBoxServiceCallback callback);

    // 主进程通知 App 生命周期变化，用于省电模式触发
    oneway void notifyAppLifecycle(boolean isForeground);
}
