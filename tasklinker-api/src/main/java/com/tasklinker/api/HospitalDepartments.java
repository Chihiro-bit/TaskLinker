package com.tasklinker.api;

/** 医院科室与诊室定义（演示用固定三科，每科固定两个诊室，可并发叫号） */
public final class HospitalDepartments {

    private HospitalDepartments() {}

    /** 演示科室列表，与叫号前缀一一对应：内科 A / 外科 B / 儿科 C */
    public static final String[] ALL = {"内科", "外科", "儿科"};

    public static final String INTERNAL = "内科";
    public static final String SURGERY = "外科";
    public static final String PEDIATRICS = "儿科";

    /** 每个科室的诊室数量（服务端按此创建 Room 数组） */
    public static final int ROOMS_PER_DEPARTMENT = 2;

    /** 诊室名称 */
    public static final String[] ROOM_NAMES = {"1号诊室", "2号诊室"};

    /** 科室 → 叫号前缀（服务端生成 "A-001" 式号码） */
    public static String prefixOf(String department) {
        if (INTERNAL.equals(department)) return "A";
        if (SURGERY.equals(department)) return "B";
        if (PEDIATRICS.equals(department)) return "C";
        return "?";
    }

    /** 诊室号 → 名称（越界时兜底返回 "N号诊室"） */
    public static String roomNameOf(int roomNo) {
        if (roomNo >= 1 && roomNo <= ROOM_NAMES.length) return ROOM_NAMES[roomNo - 1];
        return roomNo + "号诊室";
    }
}
