package com.tasklinker.server;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.tasklinker.api.HospitalDepartments;
import com.tasklinker.api.ITaskCallback;
import com.tasklinker.api.ITaskScheduler;
import com.tasklinker.api.TicketInfo;
import com.tasklinker.api.TicketPriority;
import com.tasklinker.api.TicketState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 医院叫号调度服务，运行在独立进程 ":scheduler" 中（见 AndroidManifest）。
 *
 * 领域模型：
 *  - 每个科室一个叫号队列（等待队列 + 当前就诊号），
 *    叫号由医生工作站客户端触发，全程由客户端交互驱动；
 *  - 优先号（老人/急诊）插队：等待队列按"优先级降序 → 取号时间升序"排序，
 *    比较器必须全序（见 TICKET_COMPARATOR 的第三级 taskId 兜底）。
 *
 * 关键设计（与通用任务调度版一致的工程要点）：
 *  1. AIDL 方法由 Binder 线程池并发调用，共享结构使用线程安全容器；
 *  2. 每个科室队列对象本身即锁：所有状态转换（取号/叫号/完成/过号/退号）
 *     都在 synchronized(queue) 内完成，锁外才做广播，避免持锁做 Binder 调用；
 *  3. 回调用 RemoteCallbackList 管理，内部为每个回调注册 IBinder 死亡监听，
 *     客户端进程被杀后自动移除（onCallbackDied），不持有失效 Binder；
 *  4. 广播为 oneway 异步事务，慢客户端不会阻塞服务端。
 */
public class TaskSchedulerService extends Service {

    private static final String TAG = "TaskScheduler";

    /**
     * 回调列表：子类化 RemoteCallbackList 以挂接死亡回调。
     * 客户端进程被杀 → 回调 Binder 死亡 → 自动移除后回调 onCallbackDied
     * （Binder 线程，只做日志/统计，不要做重活）。
     */
    private static class TicketCallbacks extends RemoteCallbackList<ITaskCallback> {
        @Override
        public void onCallbackDied(ITaskCallback callback, Object cookie) {
            Log.w(TAG, "client '" + cookie + "' died, callback removed automatically, total="
                    + getRegisteredCallbackCount());
        }
    }

    /**
     * 单个科室的叫号队列。对象本身即同步锁：
     * 所有队列与号状态修改都持有该锁，保证多 Binder 线程并发下的状态机一致性。
     */
    private static class DepartmentQueue {
        final String department;
        final String prefix;
        final PriorityQueue<TicketInfo> waiting; // 等待中的号（按叫号顺序出队）
        final AtomicInteger counter = new AtomicInteger(1); // 序号从 001 开始
        TicketInfo current; // 当前就诊中的号（一科同时只有一位患者就诊）

        DepartmentQueue(String department, String prefix) {
            this.department = department;
            this.prefix = prefix;
            this.waiting = new PriorityQueue<>(TICKET_COMPARATOR);
        }

        String nextTicketNo() {
            return String.format(java.util.Locale.getDefault(), "%s-%03d",
                    prefix, counter.getAndIncrement());
        }
    }

    /**
     * 叫号顺序：优先号插队。
     * 1. 优先级降序（优先号先叫）；2. 取号时间升序（先来先叫）；
     * 3. ticketId 升序兜底——比较器必须全序，返回 0 会导致优先级队列丢元素。
     */
    private static final Comparator<TicketInfo> TICKET_COMPARATOR = (a, b) -> {
        int byPriority = b.priority - a.priority;
        if (byPriority != 0) return byPriority;
        int byTime = Long.compare(a.createTime, b.createTime);
        if (byTime != 0) return byTime;
        return a.ticketId - b.ticketId;
    };

    private final TicketCallbacks callbacks = new TicketCallbacks();
    private final ConcurrentHashMap<String, DepartmentQueue> departments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, TicketInfo> tickets = new ConcurrentHashMap<>();
    private final AtomicInteger ticketIdGenerator = new AtomicInteger(0);

    /**
     * 广播期间发现的失效回调。RemoteCallbackList 不允许在 beginBroadcast 期间
     * 调用 unregister（会抛 IllegalStateException），先记账、广播结束后统一移除。
     */
    private final ConcurrentLinkedQueue<ITaskCallback> deadCallbacks = new ConcurrentLinkedQueue<>();

    @Override
    public void onCreate() {
        super.onCreate();
        for (String department : HospitalDepartments.ALL) {
            departments.put(department,
                    new DepartmentQueue(department, HospitalDepartments.prefixOf(department)));
        }
        Log.i(TAG, "TaskSchedulerService created, pid=" + Process.myPid()
                + ", departments=" + HospitalDepartments.ALL.length);
    }

    // ---------------- AIDL 实现（运行在 Binder 线程池） ----------------

    private final ITaskScheduler.Stub binder = new ITaskScheduler.Stub() {

        @Override
        public int takeNumber(TicketInfo ticket) {
            if (ticket == null) return -1;
            DepartmentQueue q = departments.get(ticket.department);
            if (q == null) return -1;

            // 服务端统一分配 id 与叫号号码，客户端传入值被覆盖
            ticket.ticketId = ticketIdGenerator.incrementAndGet();
            ticket.ticketNo = q.nextTicketNo();
            if (ticket.createTime <= 0) ticket.createTime = System.currentTimeMillis();
            if (ticket.priority <= 0) ticket.priority = TicketPriority.NORMAL;
            ticket.state = TicketState.WAITING;

            synchronized (q) {
                q.waiting.offer(ticket);
            }
            tickets.put(ticket.ticketId, ticket);
            Log.i(TAG, "takeNumber " + ticket.ticketNo + " " + ticket.patientName
                    + " " + ticket.department + "·" + TicketPriority.nameOf(ticket.priority)
                    + " from=" + clientNameOf(Binder.getCallingUid()));
            broadcast(ticket); // 通知所有大屏/客户端：有新号排队
            return ticket.ticketId;
        }

        @Override
        public int callNext(String department) {
            DepartmentQueue q = departments.get(department);
            if (q == null) return -1;
            TicketInfo called;
            synchronized (q) {
                if (q.current != null) {
                    // 同一科室同时只有一位患者就诊，先完成/过号才能叫下一位
                    Log.w(TAG, "callNext " + department + " rejected: " + q.current.ticketNo + " 就诊中");
                    return -1;
                }
                called = q.waiting.poll();
                if (called == null) {
                    Log.i(TAG, "callNext " + department + " rejected: queue empty");
                    return -1;
                }
                called.state = TicketState.CALLING;
                q.current = called;
            }
            Log.i(TAG, "callNext " + called.ticketNo + " " + called.patientName + " → " + department);
            broadcast(called);
            return called.ticketId;
        }

        @Override
        public boolean completeTicket(int ticketId) {
            return finishCurrent(ticketId, TicketState.FINISHED, "就诊完成");
        }

        @Override
        public boolean skipTicket(int ticketId) {
            return finishCurrent(ticketId, TicketState.SKIPPED, "过号");
        }

        @Override
        public boolean cancelTicket(int ticketId) {
            TicketInfo t = tickets.get(ticketId);
            if (t == null) return false;
            DepartmentQueue q = departments.get(t.department);
            if (q == null) return false;
            boolean result = false;
            synchronized (q) {
                if (t.state != TicketState.WAITING) {
                    // 只有排队中的号可退；就诊中的号由医生完成/过号
                    return false;
                }
                t.state = TicketState.CANCELLED;
                q.waiting.remove(t);
                result = true;
            }
            Log.i(TAG, "cancelTicket " + t.ticketNo + " " + t.patientName);
            broadcast(t);
            return result;
        }

        @Override
        public TicketInfo queryTicket(int ticketId) {
            TicketInfo t = tickets.get(ticketId);
            if (t == null) return null;
            DepartmentQueue q = departments.get(t.department);
            if (q == null) return null;
            // 锁内拷贝快照：Binder 按值传输，客户端拿到的是拷贝，避免读到中间态
            synchronized (q) {
                return t.copy();
            }
        }

        @Override
        public List<TicketInfo> queryQueue(String department) {
            DepartmentQueue q = departments.get(department);
            if (q == null) return null;
            List<TicketInfo> snapshot = new ArrayList<>();
            synchronized (q) {
                if (q.current != null) {
                    snapshot.add(q.current.copy()); // 首位：正在就诊
                }
                // PriorityQueue 迭代是无序的，展示前按叫号顺序排序
                List<TicketInfo> waiting = new ArrayList<>(q.waiting);
                waiting.sort(TICKET_COMPARATOR);
                for (TicketInfo t : waiting) {
                    snapshot.add(t.copy());
                }
            }
            return snapshot;
        }

        @Override
        public void registerCallback(ITaskCallback callback) {
            if (callback == null) return;
            String client = clientNameOf(Binder.getCallingUid());
            callbacks.register(callback, client);
            Log.i(TAG, "registerCallback from=" + client
                    + " total=" + callbacks.getRegisteredCallbackCount());
        }

        @Override
        public void unregisterCallback(ITaskCallback callback) {
            if (callback == null) return;
            boolean removed = callbacks.unregister(callback);
            Log.i(TAG, "unregisterCallback removed=" + removed
                    + " total=" + callbacks.getRegisteredCallbackCount());
        }
    };

    /** 就诊中的号收尾（完成/过号）：锁内转换状态并清空当前就诊位，锁外广播 */
    private boolean finishCurrent(int ticketId, int state, String action) {
        TicketInfo t = tickets.get(ticketId);
        if (t == null) return false;
        DepartmentQueue q = departments.get(t.department);
        if (q == null) return false;
        boolean result = false;
        synchronized (q) {
            if (t.state != TicketState.CALLING) return false;
            t.state = state;
            q.current = null;
            result = true;
        }
        Log.i(TAG, action + " " + t.ticketNo + " " + t.patientName);
        broadcast(t);
        return result;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind intent=" + (intent != null ? intent.getAction() : null)
                + " pid=" + Process.myPid());
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: clear " + tickets.size() + " tickets");
        callbacks.kill(); // 释放所有已注册回调
        tickets.clear();
        departments.clear();
        super.onDestroy();
    }

    // ---------------- 状态广播 ----------------

    /**
     * 向所有已注册客户端广播排队号状态变化（锁外执行）。
     * oneway 事务异步下发，客户端 Binder 线程接收，不会阻塞本线程。
     */
    private void broadcast(TicketInfo ticket) {
        int count = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                ITaskCallback cb = callbacks.getBroadcastItem(i);
                try {
                    cb.onTicketStateChanged(ticket);
                } catch (DeadObjectException e) {
                    // 竞态：广播瞬间客户端恰好死亡。记账，广播结束后统一清理
                    deadCallbacks.add(cb);
                } catch (RemoteException e) {
                    Log.w(TAG, "broadcast failed: " + e.getMessage());
                    deadCallbacks.add(cb);
                }
            }
        } finally {
            callbacks.finishBroadcast();
        }
        // 广播结束后统一移除失效回调（广播期间不允许 unregister）
        ITaskCallback cb;
        while ((cb = deadCallbacks.poll()) != null) {
            callbacks.unregister(cb);
        }
    }

    /** 由调用方 uid 反查客户端包名（用于日志与回调 cookie） */
    private String clientNameOf(int uid) {
        String[] pkgs = getPackageManager().getPackagesForUid(uid);
        return (pkgs != null && pkgs.length > 0) ? pkgs[0] : "uid:" + uid;
    }
}
