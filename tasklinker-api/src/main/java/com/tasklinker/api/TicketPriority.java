package com.tasklinker.api;

/** 排队号优先级：数值越大越先被叫号（优先号插队） */
public final class TicketPriority {

    private TicketPriority() {}

    /** 普通号 */
    public static final int NORMAL = 1;
    /** 优先号（老人、急诊等） */
    public static final int PRIORITY = 3;

    public static String nameOf(int priority) {
        return priority >= PRIORITY ? "优先号" : "普通号";
    }
}
