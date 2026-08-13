package com.tasklinker.api;

import com.tasklinker.api.TicketInfo;
import com.tasklinker.api.ITaskCallback;
import java.util.List;

/**
 * 医院叫号调度服务接口（跨应用）。
 *
 * 除 ITaskCallback 外的方法均为同步调用，运行在服务端的 Binder 线程池中
 * （多个客户端可能并发操作同一科室队列），服务端实现必须保证线程安全。
 */
interface ITaskScheduler {

    /** 取号：返回服务端分配的排队号 id（ticketId）；科室不存在返回 -1 */
    int takeNumber(in TicketInfo ticket);

    /** 指定诊室叫下一位：返回被叫排队号 id；队列为空或该诊室仍有患者就诊中返回 -1 */
    int callNext(String department, int roomNo);

    /** 就诊完成（仅"就诊中"的号可完成） */
    boolean completeTicket(int ticketId);

    /** 过号（仅"就诊中"的号可过号）：患者未到诊室，放回同优先级队尾重新排队 */
    boolean skipTicket(int ticketId);

    /** 退号（仅"排队中"的号可退） */
    boolean cancelTicket(int ticketId);

    /** 查询单个排队号快照；不存在返回 null */
    TicketInfo queryTicket(int ticketId);

    /** 查询科室队列快照：首位为正在就诊的号（若有），其余为等待中的号（按叫号顺序） */
    List<TicketInfo> queryQueue(String department);

    /** 注册状态回调（服务端用 RemoteCallbackList 管理，客户端进程死亡自动清理） */
    void registerCallback(ITaskCallback callback);

    /** 注销状态回调 */
    void unregisterCallback(ITaskCallback callback);
}
