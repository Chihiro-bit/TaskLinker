package com.chihiro.tasklinker;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.tasklinker.api.ITaskCallback;
import com.tasklinker.api.ITaskScheduler;
import com.tasklinker.api.TaskConstants;
import com.tasklinker.api.TicketInfo;
import com.tasklinker.api.TicketPriority;

import java.util.List;

/**
 * 与服务端叫号服务的连接管理（客户端侧）。
 *
 * 职责：
 *  1. bindService 建立连接（跨应用必须用显式 Intent，见 TaskConstants.serviceIntent()）；
 *  2. 连接建立后注册 ITaskCallback，并 linkToDeath 监听服务端 Binder 死亡；
 *  3. 服务端进程异常退出时（onServiceDisconnected / onBindingDied / binderDied）
 *     指数退避自动重连（1s → 2s → 4s → 8s → 10s 封顶）；
 *  4. 所有对外回调统一切换到主线程，UI 可直接使用。
 *
 * 注意：本类须在主线程构造（内部创建了主线程 Handler）。
 */
public class TaskSchedulerConnection implements ServiceConnection {

    private static final String TAG = "TaskClient";
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000L;
    private static final long MAX_RECONNECT_DELAY_MS = 10000L;

    public interface Listener {
        /** 连接状态变化（主线程回调） */
        void onStateChanged(boolean connected, String reason);

        /** 服务端广播的排队号状态变化：取号/叫号/完成/过号/退号（主线程回调） */
        void onTicketStateChanged(TicketInfo ticket);
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile ITaskScheduler scheduler;
    private volatile boolean bound;
    private boolean callbackRegistered;
    private int reconnectAttempt;
    private volatile boolean stopped; // 用户主动断开后不再重连

    /**
     * 注册到服务端的回调。
     * 方法运行在客户端进程的 Binder 线程池中，严禁直接碰 UI，
     * 一律 post 回主线程。
     */
    private final ITaskCallback.Stub taskCallback = new ITaskCallback.Stub() {
        @Override
        public void onTicketStateChanged(TicketInfo ticket) {
            mainHandler.post(() -> listener.onTicketStateChanged(ticket));
        }
    };

    /** 服务端 Binder 死亡监听（linkToDeath）：服务进程被杀时立即触发重连 */
    private final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "server binder died");
            mainHandler.post(() -> handleDisconnect("服务端进程死亡"));
        }
    };

    private final Runnable reconnectRunnable = () -> {
        if (!stopped) connect();
    };

    public TaskSchedulerConnection(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** 开始连接（幂等；若服务端未安装则自动重试） */
    public void connect() {
        stopped = false;
        if (bound) return;
        Log.i(TAG, "binding service...");
        boolean ok = context.bindService(TaskConstants.serviceIntent(), this, Context.BIND_AUTO_CREATE);
        if (!ok) {
            // 服务端未安装或被 force-stop：不会走 onServiceConnected，直接安排重试
            Log.w(TAG, "bindService returned false (server not installed?)");
            listener.onStateChanged(false, "绑定失败（服务端未安装？）");
            scheduleReconnect();
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (service == null) return;
        scheduler = ITaskScheduler.Stub.asInterface(service);
        try {
            service.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "linkToDeath failed", e);
            handleDisconnect("linkToDeath 失败");
            return;
        }
        bound = true;
        reconnectAttempt = 0;
        registerCallback();
        listener.onStateChanged(true, "已连接 " + name.getPackageName());
    }

    /**
     * 仅在服务端进程异常退出时回调；主动 unbindService 不会走到这里。
     */
    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.w(TAG, "onServiceDisconnected");
        handleDisconnect("服务连接异常断开");
    }

    /** API 26+：绑定服务的进程死亡（等价于 onServiceDisconnected 的补充路径） */
    @Override
    public void onBindingDied(ComponentName name) {
        Log.w(TAG, "onBindingDied");
        handleDisconnect("服务进程死亡");
    }

    private void handleDisconnect(String reason) {
        scheduler = null;
        bound = false;
        callbackRegistered = false;
        listener.onStateChanged(false, reason);
        if (!stopped) scheduleReconnect();
    }

    /** 指数退避重连：1s → 2s → 4s → 8s → 10s（封顶） */
    private void scheduleReconnect() {
        if (stopped) return;
        long delay = Math.min(MAX_RECONNECT_DELAY_MS,
                INITIAL_RECONNECT_DELAY_MS << Math.min(reconnectAttempt, 4));
        reconnectAttempt++;
        Log.w(TAG, "reconnect in " + delay + "ms (attempt #" + reconnectAttempt + ")");
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private void registerCallback() {
        ITaskScheduler s = scheduler;
        if (s == null) return;
        try {
            s.registerCallback(taskCallback);
            callbackRegistered = true;
            Log.i(TAG, "callback registered");
        } catch (RemoteException e) {
            handleDisconnect("注册回调失败: " + e.getMessage());
        }
    }

    // ---------------- 供 UI 调用的 API（对应服务端 5 个业务方法 + 2 个查询） ----------------

    /** 取号：返回服务端分配的 ticketId；失败返回 -1 */
    public int takeNumber(String patientName, String department, boolean priority) {
        ITaskScheduler s = scheduler;
        if (s == null) return -1;
        TicketInfo ticket = new TicketInfo();
        ticket.patientName = patientName;
        ticket.department = department;
        ticket.priority = priority ? TicketPriority.PRIORITY : TicketPriority.NORMAL;
        try {
            return s.takeNumber(ticket); // 叫号号码由服务端统一生成并返回 id
        } catch (RemoteException e) {
            handleDisconnect("取号失败: " + e.getMessage());
            return -1;
        }
    }

    /** 指定诊室叫下一位：返回被叫 ticketId；队列为空或该诊室患者未处理完返回 -1 */
    public int callNext(String department, int roomNo) {
        ITaskScheduler s = scheduler;
        if (s == null) return -1;
        try {
            return s.callNext(department, roomNo);
        } catch (RemoteException e) {
            handleDisconnect("叫号失败: " + e.getMessage());
            return -1;
        }
    }

    /** 就诊完成 */
    public boolean completeTicket(int ticketId) {
        ITaskScheduler s = scheduler;
        if (s == null) return false;
        try {
            return s.completeTicket(ticketId);
        } catch (RemoteException e) {
            handleDisconnect("操作失败: " + e.getMessage());
            return false;
        }
    }

    /** 过号（患者未到诊室）：服务端会放回同优先级队尾重新排队 */
    public boolean skipTicket(int ticketId) {
        ITaskScheduler s = scheduler;
        if (s == null) return false;
        try {
            return s.skipTicket(ticketId);
        } catch (RemoteException e) {
            handleDisconnect("操作失败: " + e.getMessage());
            return false;
        }
    }

    /** 退号（仅排队中的号可退） */
    public boolean cancelTicket(int ticketId) {
        ITaskScheduler s = scheduler;
        if (s == null) return false;
        try {
            return s.cancelTicket(ticketId);
        } catch (RemoteException e) {
            handleDisconnect("退号失败: " + e.getMessage());
            return false;
        }
    }

    /** 查询单个排队号快照；不存在返回 null */
    public TicketInfo queryTicket(int ticketId) {
        ITaskScheduler s = scheduler;
        if (s == null) return null;
        try {
            return s.queryTicket(ticketId);
        } catch (RemoteException e) {
            handleDisconnect("查询失败: " + e.getMessage());
            return null;
        }
    }

    /** 查询科室队列快照：首位为正在就诊（若有），其余为等待中的号 */
    public List<TicketInfo> queryQueue(String department) {
        ITaskScheduler s = scheduler;
        if (s == null) return null;
        try {
            return s.queryQueue(department);
        } catch (RemoteException e) {
            handleDisconnect("查询失败: " + e.getMessage());
            return null;
        }
    }

    /** 主动断开（界面 onStop/销毁时调用）：注销回调、解绑、停止一切重连 */
    public void disconnect() {
        stopped = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (callbackRegistered) {
            ITaskScheduler s = scheduler;
            if (s != null) {
                try {
                    s.unregisterCallback(taskCallback);
                } catch (RemoteException ignored) {
                    // 服务端已死则无需注销，RemoteCallbackList 会自行清理
                }
            }
            callbackRegistered = false;
        }
        if (bound) {
            try {
                context.unbindService(this);
            } catch (IllegalArgumentException ignored) {
                // 连接已失效时解绑会抛此异常，忽略
            }
            bound = false;
        }
        scheduler = null;
        Log.i(TAG, "disconnected");
    }

    private String getPackageNameSuffix() {
        String pkg = context.getPackageName();
        int i = pkg.lastIndexOf('.');
        return i >= 0 ? pkg.substring(i + 1) : pkg;
    }
}
