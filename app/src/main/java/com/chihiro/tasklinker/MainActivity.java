package com.chihiro.tasklinker;

import android.os.Bundle;
import android.speech.tts.TextToSpeech;
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
 *  - 医生工作站：双诊室并发叫号 / 就诊完成 / 过号（过号自动放回队尾）
 *  - 叫号大屏：实时显示各诊室当前就诊与等待队列，支持语音播报叫号
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
    private Spinner spRoom;
    private TextView tvRoom1;
    private TextView tvRoom2;

    // 叫号大屏
    private Spinner spDeptDisplay;
    private CheckBox cbVoice;
    private TextView tvNowServing;
    private RecyclerView rvQueue;

    private TextView tvStatus;
    private RecyclerView rvLog;

    private final List<String> logItems = new ArrayList<>();
    private final List<String> queueItems = new ArrayList<>();
    private EventLogAdapter logAdapter;
    private EventLogAdapter queueAdapter;

    /** 各科室各诊室当前就诊中的号（由服务端 CALLING 回调维护，主线程访问），key = 科室#诊室号 */
    private final Map<String, TicketInfo> currentByRoom = new HashMap<>();

    private TaskSchedulerConnection connection;
    private int role = ROLE_TAKE;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 语音播报（叫号大屏角色使用）
    private TextToSpeech tts;
    private boolean ttsReady;

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
        spRoom = findViewById(R.id.spRoom);
        tvRoom1 = findViewById(R.id.tvRoom1);
        tvRoom2 = findViewById(R.id.tvRoom2);

        spDeptDisplay = findViewById(R.id.spDeptDisplay);
        cbVoice = findViewById(R.id.cbVoice);
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

        ArrayAdapter<String> roomAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, HospitalDepartments.ROOM_NAMES);
        roomAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRoom.setAdapter(roomAdapter);

        initTts();

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
                    currentByRoom.clear();
                    updateDoctorPanel();
                    refreshQueue();
                }
            }

            @Override
            public void onTicketStateChanged(TicketInfo t) {
                // 服务端广播的每一次状态变化：取号/叫号/完成/过号重排/退号
                appendLog(describe(t));
                if (t.state == TicketState.CALLING) {
                    currentByRoom.put(keyOf(t), t);
                    // 叫号大屏语音播报
                    if (role == ROLE_DISPLAY && cbVoice.isChecked() && ttsReady) {
                        announce(t);
                    }
                } else {
                    removeByTicketId(t.ticketId); // 号离开诊室（完成/过号重排），清本地缓存
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
        spDeptDoctor.setOnItemSelectedListener(new SimpleSelectedListener(this::updateDoctorPanel));
        spRoom.setOnItemSelectedListener(new SimpleSelectedListener(this::updateDoctorPanel));

        // ---- 叫号大屏 ----
        findViewById(R.id.btnRefresh).setOnClickListener(v -> refreshQueue());
        spDeptDisplay.setOnItemSelectedListener(new SimpleSelectedListener(this::refreshQueue));

        appendLog("本客户端: " + getPackageName() + "（默认角色："
                + (role == ROLE_TAKE ? "取号机" : role == ROLE_DOCTOR ? "医生工作站" : "叫号大屏") + "）");

        // 终端类应用（取号机/医生站/大屏）需要常驻连接：
        // 切后台不注销回调，保持接收服务端广播，回到前台时界面已是最新状态
        connection.connect();
    }

    @Override
    protected void onDestroy() {
        if (connection != null) connection.disconnect();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    // ---------------- 语音播报 ----------------

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                appendLog("语音播报引擎初始化失败");
                return;
            }
            int result = tts.setLanguage(Locale.CHINA);
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
            if (!ttsReady) {
                appendLog("语音播报不可用：设备缺少中文语音数据");
            }
        });
    }

    /** 播报叫号："请 A零零三 号，患者A，到 内科 2号诊室 就诊" */
    private void announce(TicketInfo t) {
        String text = "请 " + toChineseDigits(t.ticketNo) + " 号，" + t.patientName
                + "，到 " + t.department + HospitalDepartments.roomNameOf(t.roomNo) + " 就诊";
        appendLog("🔊 " + text);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "call-" + t.ticketId);
    }

    /** 数字转中文读法（"A-003" → "A零零三"），让 TTS 朗读更自然 */
    private static String toChineseDigits(String s) {
        final char[] CN = {'零', '一', '二', '三', '四', '五', '六', '七', '八', '九'};
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c >= '0' && c <= '9' ? CN[c - '0'] : c);
        }
        return sb.toString();
    }

    // ---------------- 连接恢复 ----------------

    /** 连接（重连）建立后，从服务端拉取真实状态，修复断线期间失真的本地缓存 */
    private void resyncFromServer() {
        for (String dept : HospitalDepartments.ALL) {
            List<TicketInfo> list = connection == null ? null : connection.queryQueue(dept);
            if (list != null) {
                for (TicketInfo t : list) {
                    if (t.state == TicketState.CALLING) {
                        currentByRoom.put(keyOf(t), t);
                    }
                }
            }
        }
        updateDoctorPanel();
        if (role == ROLE_DISPLAY) refreshQueue();
    }

    private static String keyOf(TicketInfo t) {
        return t.department + "#" + t.roomNo;
    }

    private void removeByTicketId(int ticketId) {
        currentByRoom.entrySet().removeIf(e -> e.getValue().ticketId == ticketId);
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

    // ---------------- 医生工作站（多诊室） ----------------

    private void callNext() {
        String department = (String) spDeptDoctor.getSelectedItem();
        int roomNo = spRoom.getSelectedItemPosition() + 1;
        int id = connection == null ? -1 : connection.callNext(department, roomNo);
        if (id <= 0) {
            appendLog("叫号失败（" + department + HospitalDepartments.roomNameOf(roomNo)
                    + "）：该诊室有患者或队列为空");
        }
        updateDoctorPanel();
    }

    private void completeOrSkip(boolean skip) {
        String department = (String) spDeptDoctor.getSelectedItem();
        int roomNo = spRoom.getSelectedItemPosition() + 1;
        TicketInfo cur = currentByRoom.get(department + "#" + roomNo);
        if (cur == null) {
            Toast.makeText(this, HospitalDepartments.roomNameOf(roomNo) + "当前没有就诊中的号", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean ok = connection != null
                && (skip ? connection.skipTicket(cur.ticketId) : connection.completeTicket(cur.ticketId));
        if (!ok) appendLog("操作失败：" + cur.ticketNo + " 状态已变化，请刷新");
    }

    private void updateDoctorPanel() {
        String department = (String) spDeptDoctor.getSelectedItem();
        for (int roomNo = 1; roomNo <= HospitalDepartments.ROOMS_PER_DEPARTMENT; roomNo++) {
            TicketInfo cur = currentByRoom.get(department + "#" + roomNo);
            TextView tv = roomNo == 1 ? tvRoom1 : tvRoom2;
            String text = HospitalDepartments.roomNameOf(roomNo) + "："
                    + (cur == null ? "空闲" : cur.ticketNo + "  " + cur.patientName
                    + (cur.skippedCount > 0 ? "（过号" + cur.skippedCount + "次）" : ""));
            tv.setText(text);
        }
    }

    // ---------------- 叫号大屏 ----------------

    private void refreshQueue() {
        String department = (String) spDeptDisplay.getSelectedItem();
        List<TicketInfo> list = connection == null ? null : connection.queryQueue(department);
        queueItems.clear();
        if (list == null || list.isEmpty()) {
            tvNowServing.setText("当前就诊：无");
        } else {
            List<TicketInfo> calling = new ArrayList<>();
            List<TicketInfo> waiting = new ArrayList<>();
            for (TicketInfo t : list) {
                if (t.state == TicketState.CALLING) calling.add(t);
                else waiting.add(t);
            }
            if (calling.isEmpty()) {
                tvNowServing.setText("当前就诊：无");
            } else {
                StringBuilder sb = new StringBuilder("当前就诊：");
                for (TicketInfo t : calling) {
                    sb.append("\n").append(HospitalDepartments.roomNameOf(t.roomNo))
                            .append("  ").append(t.ticketNo).append("  ").append(t.patientName);
                    queueItems.add("▶ " + HospitalDepartments.roomNameOf(t.roomNo)
                            + "  " + t.ticketNo + "  " + t.patientName + "（就诊中）");
                }
                tvNowServing.setText(sb.toString());
            }
            for (TicketInfo t : waiting) {
                queueItems.add(String.format(Locale.getDefault(), "%s  %s  %s%s",
                        t.ticketNo, t.patientName,
                        t.priority >= TicketPriority.PRIORITY ? "[优先] " : "",
                        t.skippedCount > 0 ? "过号" + t.skippedCount + "次" : ""));
            }
        }
        queueAdapter.notifyDataSetChanged();
    }

    // ---------------- 日志 ----------------

    private String describe(TicketInfo t) {
        switch (t.state) {
            case TicketState.WAITING:
                if (t.skippedCount > 0) {
                    return String.format(Locale.getDefault(), "过号重排（第%d次）回到队尾：%s %s %s",
                            t.skippedCount, t.ticketNo, t.patientName, t.department);
                }
                return String.format(Locale.getDefault(), "取号 %s %s（%s·%s）排队中",
                        t.ticketNo, t.patientName, t.department, TicketPriority.nameOf(t.priority));
            case TicketState.CALLING:
                return String.format(Locale.getDefault(), "叫号 %s %s → 请到%s%s就诊",
                        t.ticketNo, t.patientName, t.department, HospitalDepartments.roomNameOf(t.roomNo));
            case TicketState.FINISHED:
                return String.format(Locale.getDefault(), "%s %s 就诊完成", t.ticketNo, t.patientName);
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
