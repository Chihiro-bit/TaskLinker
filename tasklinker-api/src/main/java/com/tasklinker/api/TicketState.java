package com.tasklinker.api;

/** 排队号状态（服务端为唯一权威，经回调/查询同步给所有客户端） */
public final class TicketState {

    private TicketState() {}

    /** 排队中（已取号，等待叫号） */
    public static final int WAITING = 0;
    /** 就诊中（已被叫号） */
    public static final int CALLING = 1;
    /** 就诊完成 */
    public static final int FINISHED = 2;
    /** 过号（叫号后患者未到诊室） */
    public static final int SKIPPED = 3;
    /** 已退号 */
    public static final int CANCELLED = 4;

    public static String nameOf(int state) {
        switch (state) {
            case WAITING:   return "排队中";
            case CALLING:   return "就诊中";
            case FINISHED:  return "就诊完成";
            case SKIPPED:   return "过号";
            case CANCELLED: return "已退号";
            default:        return "未知(" + state + ")";
        }
    }
}
