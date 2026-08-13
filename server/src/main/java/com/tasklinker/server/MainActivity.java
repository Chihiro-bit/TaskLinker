package com.tasklinker.server;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.widget.TextView;

/**
 * 服务端演示界面（运行在主进程）。
 * 叫号调度核心逻辑全部在 TaskSchedulerService（独立进程 :scheduler）中。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tvInfo = findViewById(R.id.tvInfo);
        tvInfo.setText(
                "医院叫号调度服务端\n\n"
                        + "· 本界面运行在主进程（PID: " + Process.myPid() + "）\n"
                        + "· 叫号服务 TaskSchedulerService 运行在独立进程\n"
                        + "  com.tasklinker.server:scheduler\n"
                        + "  （其 PID 见 logcat，过滤 TAG=TaskScheduler）\n"
                        + "· 科室：内科(A) / 外科(B) / 儿科(C)，每科独立叫号队列\n"
                        + "· 优先号（老人/急诊）插队\n\n"
                        + "客户端角色（3 个 flavor 可同时安装）：\n"
                        + "  TaskClient A —— 取号机：取号/退号\n"
                        + "  TaskClient B —— 医生工作站：叫下一位/就诊完成/过号\n"
                        + "  TaskClient C —— 叫号大屏：实时显示当前叫号与排队队列\n"
                        + "（任意客户端都可切换三种角色）\n\n"
                        + "核心实现见 TaskSchedulerService.java：\n"
                        + "  - 科室队列 PriorityQueue + 优先号插队\n"
                        + "  - RemoteCallbackList 向全体客户端广播状态\n"
                        + "  - 客户端死亡自动清理回调，杜绝内存泄漏");

        // startService：即使没有任何客户端绑定也保持服务运行；
        // 客户端 bindService 同样会拉起该服务（BIND_AUTO_CREATE）。
        startService(new Intent(this, TaskSchedulerService.class));
    }

    @Override
    protected void onDestroy() {
        // 停止"已启动"状态；若仍有客户端绑定，服务会继续存活
        stopService(new Intent(this, TaskSchedulerService.class));
        super.onDestroy();
    }
}
