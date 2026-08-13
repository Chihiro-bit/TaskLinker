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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 医院叫号调度服务，运行在独立进程 ":scheduler" 中（见 AndroidManifest）。
 *
 * 领域模型：
 *  - 每个科室 N 个诊室（Room），多诊室可并发就诊；
 *  - 等待队列按"优先级降序 → 取号时间升序 → ticketId 升序"全序排序，
 *    优先号（老人/急诊）插队；
 *  - 叫号超时自动过号：callNext 后 AUTO_SKIP_TIMEOUT_SECONDS 内未完成/过号，
 *    由 ScheduledExecutorService 触发自动放回队尾；
 *  - 过号重排：过号（手动或超时）不是终态——skippedCount++ 并重置取号时间，
 *    排到同优先级队尾继续等待叫号，患者不会丢号。
 *
 * 工程要点（与前一版本一致）：
 *  1. AIDL 方法由 Binder 线程池并发调用，共享结构使用线程安全容器；
 *  2. 每个科室队列对象本身即锁：所有状态转换都在 synchronized(queue) 内完成，
 *     锁外才做定时器操作与广播，避免持锁做耗时/跨进程操作；
 *  3. 回调用 RemoteCallbackList 管理，客户端进程被杀自动清理（onCallbackDied）；
 *  4. 广播为 oneway 异步事务，慢客户端不会阻塞服务端。
 */
public class TaskSchedulerService extends Service {

    private static final String TAG = "TaskScheduler";

    /** 叫号超时自动过号：医生叫号后 N 秒内未完成/过号，自动放回队尾 */
    private static final long AUTO_SKIP_TIMEOUT_SECONDS = 20L;

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

    /** 诊室：一个科室多个诊室，可同时各有一位患者就诊 */
    private static class Room {
        final int roomNo;
        TicketInfo current; // 该诊室当前就诊中的号（null 表示空闲）

        Room(int roomNo) {
            this.roomNo = roomNo;
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
        final Room[] rooms;                       // 诊室（多诊室并发就诊）
        final AtomicInteger counter = new AtomicInteger(1);

        DepartmentQueue(String department, String prefix, int roomCount) {
            this.department = department;
            this.prefix = prefix;
            this.waiting = new PriorityQueue<>(TICKET_COMPARATOR);
            this.rooms = new Room[roomCount];
            for (int i = 0; i < roomCount; i++) {
                rooms[i] = new Room(i + 1);
            }
        }

        String nextTicketNo() {
            return String.format(java.util.Locale.getDefault(), "%s-%03d",
                    prefix, counter.getAndIncrement());
        }

        Room room(int roomNo) {
            return (roomNo >= 1 && roomNo <= rooms.length) ? rooms[roomNo - 1] : null;
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

    /** 广播期间发现的失效回调。RemoteCallbackList 不允许在广播期间 unregister，先记账后统一移除 */
    private final ConcurrentLinkedQueue<ITaskCallback> deadCallbacks = new ConcurrentLinkedQueue<>();

    /** 叫号超时自动过号的定时器（单线程足够：任务只是抢锁做一次状态检查） */
    private final ScheduledExecutorService autoSkipExecutor =
            Executors.newScheduledThreadPool(1, r -> new Thread(r, "AutoSkip"));
    private final ConcurrentHashMap<Integer, ScheduledFuture<?>> autoSkipTimers = new ConcurrentHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        for (String department : HospitalDepartments.ALL) {
            departments.put(department, new DepartmentQueue(department,
                    HospitalDepartments.prefixOf(department), HospitalDepartments.ROOMS_PER_DEPARTMENT));
        }
        Log.i(TAG, "TaskSchedulerService created, pid=" + Process.myPid()
                + ", departments=" + HospitalDepartments.ALL.length
                + ", roomsPerDepartment=" + HospitalDepartments.ROOMS_PER_DEPARTMENT
                + ", autoSkipTimeout=" + AUTO_SKIP_TIMEOUT_SECONDS + "s");
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
            ticket.roomNo = 0;
            ticket.skippedCount = 0;

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
        public int callNext(String department, int roomNo) {
            DepartmentQueue q = departments.get(department);
            if (q == null) return -1;
            Room room = q.room(roomNo);
            if (room == null) return -1;
            TicketInfo called;
            synchronized (q) {
                if (room.current != null) {
                    // 该诊室仍有患者就诊中，先完成/过号才能叫下一位
                    Log.w(TAG, "callNext " + department + " " + HospitalDepartments.roomNameOf(roomNo)
                            + " rejected: " + room.current.ticketNo + " 就诊中");
                    return -1;
                }
                called = q.waiting.poll();
                if (called == null) {
                    Log.i(TAG, "callNext " + department + " " + HospitalDepartments.roomNameOf(roomNo)
                            + " rejected: queue empty");
                    return -1;
                }
                called.state = TicketState.CALLING;
                called.roomNo = roomNo;
                room.current = called;
            }
            // 锁外：启动超时自动过号定时器 + 广播
            scheduleAutoSkip(called.ticketId);
            Log.i(TAG, "callNext " + called.ticketNo + " " + called.patientName
                    + " → " + department + HospitalDepartments.roomNameOf(roomNo)
                    + "（" + AUTO_SKIP_TIMEOUT_SECONDS + "s 未处理将自动过号重排）");
            broadcast(called);
            return called.ticketId;
        }

        @Override
        public boolean completeTicket(int ticketId) {
            return finishTicket(ticketId, false);
        }

        @Override
        public boolean skipTicket(int ticketId) {
            return finishTicket(ticketId, true);
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
                    // 只有排队中的号可退；就诊中的号由医生完成/过号（或超时自动过号）
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
                // 前部：各诊室正在就诊的号（按诊室顺序，多诊室并发）
                for (Room room : q.rooms) {
                    if (room.current != null) {
                        snapshot.add(room.current.copy());
                    }
                }
                // 后部：等待队列（PriorityQueue 迭代无序，展示前按叫号顺序排序）
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

    // ---------------- 就诊收尾：完成 / 过号重排 ----------------

    /** 就诊中的号收尾。skip=true 过号（放回队尾重排）；否则就诊完成 */
    private boolean finishTicket(int ticketId, boolean skip) {
        TicketInfo t = tickets.get(ticketId);
        if (t == null) return false;
        DepartmentQueue q = departments.get(t.department);
        if (q == null) return false;
        boolean result = false;
        synchronized (q) {
            if (t.state != TicketState.CALLING) return false;
            if (skip) {
                requeueSkippedLocked(q, t); // 过号：重排回队尾
            } else {
                t.state = TicketState.FINISHED;
                t.roomNo = 0;
                clearRoomLocked(q, t);
            }
            result = true;
        }
        cancelAutoSkip(ticketId); // 定时器使命结束
        Log.i(TAG, (skip ? "skipTicket 过号重排（第" + t.skippedCount + "次）" : "completeTicket 就诊完成")
                + " " + t.ticketNo + " " + t.patientName);
        broadcast(t);
        return result;
    }

    /** 过号重排（必须在队列锁内调用）：计数 +1、重置取号时间（排到同优先级队尾）、回到等待队列 */
    private void requeueSkippedLocked(DepartmentQueue q, TicketInfo t) {
        t.skippedCount++;
        t.createTime = System.currentTimeMillis(); // 时间重置 → 比较器自然排到同优先级尾部
        t.state = TicketState.WAITING;
        t.roomNo = 0;
        clearRoomLocked(q, t);
        q.waiting.offer(t);
    }

    private void clearRoomLocked(DepartmentQueue q, TicketInfo t) {
        for (Room room : q.rooms) {
            if (room.current == t) {
                room.current = null;
                return;
            }
        }
    }

    // ---------------- 叫号超时自动过号（ScheduledExecutorService） ----------------

    private void scheduleAutoSkip(int ticketId) {
        ScheduledFuture<?> future = autoSkipExecutor.schedule(
                () -> autoSkipIfStillCalling(ticketId),
                AUTO_SKIP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        autoSkipTimers.put(ticketId, future);
    }

    /** 医生及时处理（完成/过号）后取消定时器，避免误触发 */
    private void cancelAutoSkip(int ticketId) {
        ScheduledFuture<?> future = autoSkipTimers.remove(ticketId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** 定时器触发：号仍处于就诊中 → 自动过号重排（锁内检查，与医生操作竞争同一把锁，状态机不会乱） */
    private void autoSkipIfStillCalling(int ticketId) {
        TicketInfo t = tickets.get(ticketId);
        if (t == null) return;
        DepartmentQueue q = departments.get(t.department);
        if (q == null) return;
        synchronized (q) {
            if (t.state != TicketState.CALLING) return; // 已被医生处理，忽略
            requeueSkippedLocked(q, t);
        }
        autoSkipTimers.remove(ticketId);
        Log.w(TAG, "autoSkip timeout: " + t.ticketNo + " " + t.patientName
                + " 超时未到，自动过号重排（第" + t.skippedCount + "次）");
        broadcast(t);
    }

    // ---------------- 生命周期 ----------------

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind intent=" + (intent != null ? intent.getAction() : null)
                + " pid=" + Process.myPid());
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: clear " + tickets.size() + " tickets, shutdown autoSkip executor");
        autoSkipExecutor.shutdownNow();
        autoSkipTimers.clear();
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
