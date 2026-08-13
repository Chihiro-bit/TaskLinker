package com.tasklinker.api;

/** 医院科室定义（演示用固定三科，每个科室独立叫号队列） */
public final class HospitalDepartments {

    private HospitalDepartments() {}

    /** 演示科室列表，与叫号前缀一一对应：内科 A / 外科 B / 儿科 C */
    public static final String[] ALL = {"内科", "外科", "儿科"};

    public static final String INTERNAL = "内科";
    public static final String SURGERY = "外科";
    public static final String PEDIATRICS = "儿科";

    /** 科室 → 叫号前缀（服务端生成 "A-001" 式号码） */
    public static String prefixOf(String department) {
        if (INTERNAL.equals(department)) return "A";
        if (SURGERY.equals(department)) return "B";
        if (PEDIATRICS.equals(department)) return "C";
        return "?";
    }
}
