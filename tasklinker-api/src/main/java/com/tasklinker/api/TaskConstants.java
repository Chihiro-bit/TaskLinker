package com.tasklinker.api;

import android.content.Intent;

/** 跨应用连接所需的常量 */
public final class TaskConstants {

    private TaskConstants() {}

    /** 服务端应用包名 */
    public static final String SERVER_PACKAGE = "com.tasklinker.server";
    /** 服务端调度服务的完整类名 */
    public static final String SERVICE_CLASS = "com.tasklinker.server.TaskSchedulerService";
    /** 调度服务的 action（配合 setPackage 组成显式 Intent） */
    public static final String SERVICE_ACTION = "com.tasklinker.api.action.TASK_SCHEDULER";
    /** 绑定调度服务所需的自定义权限 */
    public static final String BIND_PERMISSION = "com.tasklinker.permission.BIND_TASK_SCHEDULER";

    /**
     * 构造绑定调度服务的显式 Intent。
     * 跨应用绑定服务必须使用显式 Intent（Android 5.0+ 禁止隐式 Intent 绑定服务）。
     */
    public static Intent serviceIntent() {
        return new Intent(SERVICE_ACTION).setPackage(SERVER_PACKAGE);
    }
}
