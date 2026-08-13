package com.chihiro.tasklinker;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tasklinker.api.HospitalDepartments;
import com.tasklinker.api.TicketInfo;
import com.tasklinker.api.TicketPriority;
import com.tasklinker.api.TicketState;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 客户端主界面，三种角色可切换（同一份代码，3 个 flavor 可同时安装）：
 *  - 取号机：取号（普通号/优先号）
 *  - 医生工作站：叫下一位 / 就诊完成 / 过号
 *  - 叫号大屏：实时显示当前叫号与等待队列（服务端每次状态变化自动刷新）
 *
 * 默认角色按 flavor 区分：TaskClient A → 取号机，B → 医生工作站，C → 叫号大屏。
 */
public class MainActivity extends AppCompatActivity {

    private static final int ROLE_TAKE = 0;
    private static final int ROLE_DOCTOR = 1;
    private static final int ROLE_DISPLAY = 2;
    private static final int MAX_LOG_LINES = 300;

    private RadioGroup rgRole;
    private View panelTake;
    private View panelDoctor;
    private View panelDisplay;

    // 取号机
    private Spinner spDept;
    private EditText etName;
    private CheckBox cbPriority;
    private TextView tvTakeResult;

    // 医生工作站
    private Spinner spDeptDoctor;
    private TextView tvCurrent;

    // 叫号大屏
    private Spinner spDeptDisplay;
    private TextView tvNowServing;
    private RecyclerView rvQueue;

    private TextView tvStatus;
    private RecyclerView rvLog;

    private final List<String> logItems = new ArrayList<>();
    private final List<String> queueItems = new ArrayList<>();
    private EventLogAdapter logAdapter;
    private EventLogAdapter queueAdapter;

    /** 各科室当前就诊中的号（由服务端 CALLING 回调维护，主线程访问） */
    private final Map<String, TicketInfo> currentByDept = new HashMap<>();

    private TaskSchedulerConnection connection;
    private int role = ROLE_TAKE;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        rgRole = findViewById(R.id.rgRole);
        panelTake = findViewById(R.id.panelTake);
        panelDoctor = findViewById(R.id.panelDoctor);
        panelDisplay = findViewById(R.id.panelDisplay);

        spDept = findViewById(R.id.spDept);
        etName = findViewById(R.id.etName);
        cbPriority = findViewById(R.id.cbPriority);
        tvTakeResult = findViewById(R.id.tvTakeResult);

        spDeptDoctor = findViewById(R.id.spDeptDoctor);
        tvCurrent = findViewById(R.id.tvCurrent);

        spDeptDisplay = findViewById(R.id.spDeptDisplay);
        tvNowServing = findViewById(R.id.tvNowServing);
        rvQueue = findViewById(R.id.rvQueue);

        rvLog = findViewById(R.id.rvLog);
        logAdapter = new EventLogAdapter(logItems);
        rvLog.setLayoutManager(new LinearLayoutManager(this));
        rvLog.setAdapter(logAdapter);

        queueAdapter = new EventLogAdapter(queueItems);
        rvQueue.setLayoutManager(new LinearLayoutManager(this));
        rvQueue.setAdapter(queueAdapter);

        ArrayAdapter<String> deptAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, HospitalDepartments.ALL);
        deptAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDept.setAdapter(deptAdapter);
        spDeptDoctor.setAdapter(deptAdapter);
        spDeptDisplay.setAdapter(deptAdapter);

        // 连接管理类：所有回调已切换到主线程，可直接更新 UI
        connection = new TaskSchedulerConnection(this, new TaskSchedulerConnection.Listener() {
            @Override
            public void onStateChanged(boolean connected, String reason) {
                tvStatus.setText((connected ? "● 已连接  " : "○ 未连接  ") + reason);
                appendLog(connected ? "已连接服务端" : "连接断开：" + reason);
                if (connected) {
                    resyncFromServer(); // 重连后从服务端拉取真实状态，修复本地缓存
                } else {
                    // 断线期间本地"当前就诊"缓存不可信，清空
                    currentByDept.clear();
                    updateDoctorPanel();
                    refreshQueue();
                }
            }

            @Override
            public void onTicketStateChanged(TicketInfo t) {
                // 服务端广播的每一次状态变化：取号/叫号/完成/过号/退号
                appendLog(describe(t));
                if (t.state == TicketState.CALLING) {
                    currentByDept.put(t.department, t);
                } else if (t.state == TicketState.FINISHED || t.state == TicketState.SKIPPED) {
                    currentByDept.remove(t.department);
                }
                updateDoctorPanel();
                if (role == ROLE_DISPLAY) refreshQueue(); // 大屏实时刷新
            }
        });

        // 角色切换：默认按 flavor 分工（A 取号机 / B 医生工作站 / C 大屏）
        String pkg = getPackageName();
        role = pkg.endsWith("clientb") ? ROLE_DOCTOR : pkg.endsWith("clientc") ? ROLE_DISPLAY : ROLE_TAKE;
        rgRole.check(role == ROLE_DOCTOR ? R.id.rbDoctor : role == ROLE_DISPLAY ? R.id.rbDisplay : R.id.rbTake);
        rgRole.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbDoctor) role = ROLE_DOCTOR;
            else if (checkedId == R.id.rbDisplay) role = ROLE_DISPLAY;
            else role = ROLE_TAKE;
            showPanels();
            if (role == ROLE_DISPLAY) refreshQueue();
        });
        showPanels();

        // ---- 取号机 ----
        findViewById(R.id.btnTake).setOnClickListener(v -> takeNumber());

        // ---- 医生工作站 ----
        findViewById(R.id.btnCallNext).setOnClickListener(v -> callNext());
        findViewById(R.id.btnComplete).setOnClickListener(v -> completeOrSkip(false));
        findViewById(R.id.btnSkip).setOnClickListener(v -> completeOrSkip(true));
        spDeptDoctor.setOnItemSelectedListener(new SimpleSelectedListener(() -> updateDoctorPanel()));

        // ---- 叫号大屏 ----
        findViewById(R.id.btnRefresh).setOnClickListener(v -> refreshQueue());
        spDeptDisplay.setOnItemSelectedListener(new SimpleSelectedListener(() -> refreshQueue()));

        appendLog("本客户端: " + getPackageName() + "（默认角色："
                + (role == ROLE_TAKE ? "取号机" : role == ROLE_DOCTOR ? "医生工作站" : "叫号大屏") + "）");

        // 终端类应用（取号机/医生站/大屏）需要常驻连接：
        // 切后台不注销回调，保持接收服务端广播，回到前台时界面已是最新状态
        connection.connect();
    }

    @Override
    protected void onDestroy() {
        if (connection != null) connection.disconnect();
        super.onDestroy();
    }

    /** 连接（重连）建立后，从服务端拉取真实状态，修复断线期间失真的本地缓存 */
    private void resyncFromServer() {
        String dept = (String) spDeptDoctor.getSelectedItem();
        List<TicketInfo> list = connection == null ? null : connection.queryQueue(dept);
        if (list != null && !list.isEmpty() && list.get(0).state == TicketState.CALLING) {
            currentByDept.put(dept, list.get(0)); // 恢复"当前就诊"
        }
        updateDoctorPanel();
        if (role == ROLE_DISPLAY) refreshQueue();
    }

    private void showPanels() {
        panelTake.setVisibility(role == ROLE_TAKE ? View.VISIBLE : View.GONE);
        panelDoctor.setVisibility(role == ROLE_DOCTOR ? View.VISIBLE : View.GONE);
        panelDisplay.setVisibility(role == ROLE_DISPLAY ? View.VISIBLE : View.GONE);
    }

    // ---------------- 取号机 ----------------

    private void takeNumber() {
        String department = (String) spDept.getSelectedItem();
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            // 未填姓名时按客户端身份自动命名，便于多客户端演示区分
            name = "患者" + getPackageName().substring(getPackageName().length() - 1).toUpperCase(Locale.getDefault());
        }
        boolean priority = cbPriority.isChecked();

        int id = connection == null ? -1 : connection.takeNumber(name, department, priority);
        if (id > 0) {
            TicketInfo t = connection.queryTicket(id); // 回查取到服务端生成的叫号号码
            if (t != null) {
                tvTakeResult.setText("取号成功：" + t.ticketNo + "（" + TicketPriority.nameOf(t.priority) + "）");
                appendLog("取号 " + t.ticketNo + "  " + t.patientName + "  "
                        + t.department + "·" + TicketPriority.nameOf(t.priority));
            }
        } else {
            tvTakeResult.setText("取号失败：未连接");
        }
    }

    // ---------------- 医生工作站 ----------------

    private void callNext() {
        String department = (String) spDeptDoctor.getSelectedItem();
        int id = connection == null ? -1 : connection.callNext(department);
        if (id <= 0) {
            appendLog("叫号失败（" + department + "）：队列为空或当前患者尚未完成/过号");
        }
        updateDoctorPanel();
    }

    private void completeOrSkip(boolean skip) {
        String department = (String) spDeptDoctor.getSelectedItem();
        TicketInfo cur = currentByDept.get(department);
        if (cur == null) {
            Toast.makeText(this, "该科室当前没有就诊中的号", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean ok = connection != null
                && (skip ? connection.skipTicket(cur.ticketId) : connection.completeTicket(cur.ticketId));
        if (!ok) appendLog("操作失败：" + cur.ticketNo + " 状态已变化，请刷新");
    }

    private void updateDoctorPanel() {
        String department = (String) spDeptDoctor.getSelectedItem();
        TicketInfo cur = currentByDept.get(department);
        tvCurrent.setText(cur == null ? "当前就诊：无"
                : "当前就诊：" + cur.ticketNo + "  " + cur.patientName
                + "（" + TicketPriority.nameOf(cur.priority) + "）");
    }

    // ---------------- 叫号大屏 ----------------

    private void refreshQueue() {
        String department = (String) spDeptDisplay.getSelectedItem();
        List<TicketInfo> list = connection == null ? null : connection.queryQueue(department);
        queueItems.clear();
        if (list == null || list.isEmpty()) {
            tvNowServing.setText("当前叫号：无");
        } else {
            int start = 0;
            if (list.get(0).state == TicketState.CALLING) {
                TicketInfo cur = list.get(0);
                tvNowServing.setText("当前叫号：" + cur.ticketNo + "  " + cur.patientName
                        + "（" + TicketPriority.nameOf(cur.priority) + "）");
                queueItems.add("▶ " + cur.ticketNo + "  " + cur.patientName + "（就诊中）");
                start = 1;
            } else {
                tvNowServing.setText("当前叫号：无");
            }
            for (int i = start; i < list.size(); i++) {
                TicketInfo t = list.get(i);
                queueItems.add(String.format(Locale.getDefault(), "%s  %s  %s",
                        t.ticketNo, t.patientName,
                        t.priority >= TicketPriority.PRIORITY ? "[优先]" : ""));
            }
        }
        queueAdapter.notifyDataSetChanged();
    }

    // ---------------- 日志 ----------------

    private String describe(TicketInfo t) {
        switch (t.state) {
            case TicketState.WAITING:
                return String.format(Locale.getDefault(), "取号 %s %s（%s·%s）排队中",
                        t.ticketNo, t.patientName, t.department, TicketPriority.nameOf(t.priority));
            case TicketState.CALLING:
                return String.format(Locale.getDefault(), "叫号 %s %s → 请到%s就诊",
                        t.ticketNo, t.patientName, t.department);
            case TicketState.FINISHED:
                return String.format(Locale.getDefault(), "%s %s 就诊完成", t.ticketNo, t.patientName);
            case TicketState.SKIPPED:
                return String.format(Locale.getDefault(), "%s %s 过号（未到诊室）", t.ticketNo, t.patientName);
            case TicketState.CANCELLED:
                return String.format(Locale.getDefault(), "%s %s 已退号", t.ticketNo, t.patientName);
            default:
                return t.toString();
        }
    }

    private void appendLog(String line) {
        logItems.add("[" + timeFormat.format(new Date()) + "] " + line);
        while (logItems.size() > MAX_LOG_LINES) logItems.remove(0);
        logAdapter.notifyDataSetChanged();
        rvLog.scrollToPosition(logItems.size() - 1);
    }

    /** 简单的 ItemSelectedListener 适配（避免样板代码） */
    private static class SimpleSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable action;

        SimpleSelectedListener(Runnable action) {
            this.action = action;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            action.run();
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
