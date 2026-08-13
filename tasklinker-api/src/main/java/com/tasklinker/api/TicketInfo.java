package com.tasklinker.api;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Locale;

/**
 * 排队号信息（自定义 Parcelable，经 Binder 在取号机/医生站/大屏与服务端之间按值传输）。
 *
 * 字段：
 *  - ticketId     全局唯一 id，由服务端统一分配
 *  - ticketNo     叫号号码（如 "A-003"），服务端按科室前缀 + 递增序号生成
 *  - patientName  患者姓名
 *  - department   科室（内科/外科/儿科，见 HospitalDepartments）
 *  - priority     优先级：普通号 / 优先号（老人、急诊），优先号插队（见 TicketPriority）
 *  - createTime   取号时间戳，同优先级按先来后到排序
 *  - state        排队号状态（排队中/就诊中/就诊完成/过号/已退号，见 TicketState）
 */
public class TicketInfo implements Parcelable {

    public int ticketId;
    public String ticketNo;
    public String patientName;
    public String department;
    public int priority;
    public long createTime;
    public int state;

    public TicketInfo() {
        ticketNo = "";
        patientName = "";
        department = "";
        priority = TicketPriority.NORMAL;
        state = TicketState.WAITING;
    }

    protected TicketInfo(Parcel in) {
        ticketId = in.readInt();
        ticketNo = in.readString();
        patientName = in.readString();
        department = in.readString();
        priority = in.readInt();
        createTime = in.readLong();
        state = in.readInt();
    }

    /** 拷贝一份快照（queryTicket / queryQueue 返回时使用，避免读到并发中间态） */
    public TicketInfo copy() {
        TicketInfo t = new TicketInfo();
        t.ticketId = ticketId;
        t.ticketNo = ticketNo;
        t.patientName = patientName;
        t.department = department;
        t.priority = priority;
        t.createTime = createTime;
        t.state = state;
        return t;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(ticketId);
        dest.writeString(ticketNo);
        dest.writeString(patientName);
        dest.writeString(department);
        dest.writeInt(priority);
        dest.writeLong(createTime);
        dest.writeInt(state);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<TicketInfo> CREATOR = new Creator<TicketInfo>() {
        @Override
        public TicketInfo createFromParcel(Parcel in) {
            return new TicketInfo(in);
        }

        @Override
        public TicketInfo[] newArray(int size) {
            return new TicketInfo[size];
        }
    };

    @Override
    public String toString() {
        return String.format(Locale.getDefault(), "%s %s %s·%s [%s]",
                ticketNo, patientName, department, TicketPriority.nameOf(priority), TicketState.nameOf(state));
    }
}
