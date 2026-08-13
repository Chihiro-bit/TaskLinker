# TaskLinker —— 基于 AIDL 的跨应用医院叫号系统

一个完整的 Android 多进程 IPC 示例项目：**服务端 App** 在独立进程中提供医院叫号调度能力，**3 个客户端 App**（同一份代码的 3 个 flavor，可同时安装）分别扮演**取号机 / 医生工作站 / 叫号大屏**，通过 AIDL 实时交互。

核心能力：
- 取号排队（**优先号插队**：老人/急诊先叫号）
- **多诊室并发叫号**（每科 2 个诊室，可同时就诊）
- **叫号超时自动过号**（ScheduledExecutorService，20 秒未处理自动放回队尾）
- **过号重排回队尾**（过号不丢号，skippedCount 计数，同优先级重新排队）
- **语音播报**（大屏 TextToSpeech 播报"请A-003号到2号诊室就诊"，可开关）
- 全部状态变化由服务端 oneway 广播给所有已注册客户端，大屏实时刷新

## 架构总览

```
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ TaskClient A │ │ TaskClient B │ │ TaskClient C │
│ 取号机       │ │ 医生工作站   │ │ 叫号大屏     │
│ 取号/退号    │ │ 双诊室叫号   │ │ 当前叫号+队列│
│              │ │ 完成/过号    │ │ 语音播报     │
└──────┬───────┘ └──────┬───────┘ └──────┬───────┘
       │  takeNumber    │  callNext(dept, roomNo)
       │  cancelTicket  │  complete/skip
       └────────────────┼────────────────┘
              bindService + registerCallback(ITaskCallback)
                        ▼
        ┌──────────────────────────────────────────────┐
        │ 服务端 App 独立进程 com.tasklinker.server:scheduler│
        │  TaskSchedulerService                        │
        │  ┌─────────────────────────────────────────┐ │
        │  │ 内科 A：等待队列 + [1号诊室][2号诊室]     │ │
        │  │ 外科 B：等待队列 + [1号诊室][2号诊室]     │ │
        │  │ 儿科 C：等待队列 + [1号诊室][2号诊室]     │ │
        │  │   优先号插队：优先级↓ → 取号时间↑ → id↑  │ │
        │  └─────────────────────────────────────────┘ │
        │  ScheduledExecutorService（20s 超时自动过号） │
        │  RemoteCallbackList<ITaskCallback>           │
        │  （死亡监听自动清理 + oneway 广播到全体客户端） │
        └──────────────────────────────────────────────┘
                        ▲
         onTicketStateChanged(ticket)：取号/叫号/完成/过号重排/退号
```

## 模块结构

```
TaskLinker/
├── tasklinker-api/              共享 AIDL 接口库（两端共同依赖，保证定义一致）
│   └── src/main/
│       ├── aidl/com/tasklinker/api/
│       │   ├── TicketInfo.aidl       Parcelable 声明
│       │   ├── ITaskCallback.aidl    状态回调接口（oneway）
│       │   └── ITaskScheduler.aidl   叫号调度服务接口（9 个方法）
│       └── java/com/tasklinker/api/
│           ├── TicketInfo.java       排队号 Parcelable（含 roomNo/skippedCount 扩展字段）
│           ├── TicketState.java      状态常量：排队中/就诊中/完成/退号
│           ├── TicketPriority.java   优先级：普通号/优先号
│           ├── HospitalDepartments.java  科室与诊室定义（内科A/外科B/儿科C，每科2诊室）
│           └── TaskConstants.java    服务地址/权限/显式 Intent 构造
├── server/                       服务端 App（com.tasklinker.server）
│   └── src/main/
│       ├── AndroidManifest.xml       服务运行在独立进程 :scheduler + 自定义权限
│       └── java/com/tasklinker/server/
│           ├── TaskSchedulerService.java  ★核心：科室队列/多诊室/超时过号/回调广播
│           └── MainActivity.java         演示界面（主进程）
└── app/                          客户端 App（3 个 flavor，可同时安装 3 个实例）
    └── src/main/
        ├── AndroidManifest.xml       使用权限 + <queries> 包可见性
        └── java/com/chihiro/tasklinker/
            ├── TaskSchedulerConnection.java  ★绑定/回调注册/死亡监听/指数退避重连
            ├── MainActivity.java            三角色界面（取号机/医生站/大屏+语音）
            └── EventLogAdapter.java         列表适配器（日志/队列共用）
```

## 快速开始

环境要求：Android Studio（本项目模板自带 Gradle 9.4.1 + AGP 9.2.1，JDK 21，compileSdk 36，minSdk 24）。

> **AGP 9 注意**：本项目的三个模块都显式开启了 `buildFeatures { aidl = true }`。AGP 9 中 AIDL 编译默认关闭——不开启时 `.aidl` 文件被静默忽略，会报"找不到符号 ITaskScheduler/ITaskCallback"。

1. 用 Android Studio 打开本项目，等待 Gradle Sync（同步后若 import 仍报红，先 Build → Make Project，再不行 Invalidate Caches）。
2. 构建并安装：
   ```bash
   ./gradlew :server:assembleDebug          # 服务端 APK
   ./gradlew :app:assembleClientADebug      # 客户端 A（B/C 同理）
   # 或一次构建全部：
   ./gradlew assembleDebug
   ```
   服务端 APK：`server/build/outputs/apk/debug/server-debug.apk`
   客户端 APK：`app/build/outputs/apk/clientA/debug/app-clientA-debug.apk` 等。

   **注意安装顺序：先装服务端**（自定义权限的定义方），再装客户端（权限使用方）。

3. 演示剧本（3 台客户端同时运行）：
   - 打开服务端 App（`TaskLinker Server`）→ 叫号服务在独立进程启动；
   - 同时打开 `TaskClient A/B/C`，三个界面均显示"● 已连接"；
   - **取号机**（默认是 A）：选"内科"，取 2~3 个号，可勾"优先号"；
   - **医生工作站**（默认是 B）：选"1号诊室"叫下一位 → 再切"2号诊室"叫下一位——**两诊室同时就诊**；
   - **叫号大屏**（默认是 C）：实时显示各诊室当前就诊与等待队列，勾选"语音播报"后每次叫号自动朗读；
   - 什么都不做等 20 秒 → 两诊室的号**超时自动过号**，回到队尾（大屏出现"过号1次"标记）；
   - 医生站对就诊中的号点"过号" → 手动重排（过号 N 次计数）；点"就诊完成" → 号正常离队。

## AIDL 接口设计

### ITaskScheduler.aidl（同步调用，服务端 Binder 线程池并发执行）

```aidl
interface ITaskScheduler {
    int  takeNumber(in TicketInfo ticket);            // 取号，返回 ticketId
    int  callNext(String department, int roomNo);     // 指定诊室叫下一位，返回被叫 ticketId
    boolean completeTicket(int ticketId);             // 就诊完成
    boolean skipTicket(int ticketId);                 // 过号：放回同优先级队尾重排
    boolean cancelTicket(int ticketId);               // 退号（仅排队中可退）
    TicketInfo queryTicket(int ticketId);             // 单号查询快照
    List<TicketInfo> queryQueue(String department);   // 科室队列：前部为各诊室就诊中，其余按叫号顺序
    void registerCallback(ITaskCallback callback);
    void unregisterCallback(ITaskCallback callback);
}
```

`callNext` 的约束：指定诊室仍有患者就诊中，或等待队列为空时返回 -1（每诊室同时只接一位患者）。

### ITaskCallback.aidl（oneway：单向异步，慢客户端不阻塞广播）

```aidl
oneway interface ITaskCallback {
    void onTicketStateChanged(in TicketInfo ticket); // 状态见 TicketState
}
```

### TicketInfo（Parcelable）

| 字段 | 类型 | 说明 |
|---|---|---|
| ticketId | int | 服务端统一分配（客户端传值被覆盖） |
| ticketNo | String | 叫号号码，如 "A-003"（科室前缀 + 递增序号） |
| patientName | String | 患者姓名 |
| department | String | 科室（内科/外科/儿科） |
| priority | int | 普通号=1 / 优先号=3（老人、急诊插队） |
| createTime | long | 取号时间戳，同优先级先来先叫；过号重排时重置（排到队尾） |
| state | int | 排队中/就诊中/完成/退号 |
| roomNo | int | 当前所在诊室（1/2/...，排队中与已结束为 0） |
| skippedCount | int | 过号重排次数（过号不丢号，客户端凭此显示"过号N次"） |

## 服务端设计（TaskSchedulerService）

### 1. 科室队列 + 多诊室模型

每个科室一个 `DepartmentQueue`：`PriorityQueue<TicketInfo> waiting`（等待队列）+ `Room[] rooms`（每科 2 个诊室，`Room.current` 为该诊室当前就诊号）+ 序号计数器（生成 "A-001" 式号码）。多诊室天然支持并发：两个诊室可同时各有患者就诊，`queryQueue` 返回前部为各诊室就诊中的号（按诊室顺序）、后部为等待队列。

### 2. 叫号顺序 = 优先号插队

比较器三级排序：**优先级降序（优先号先叫）→ 取号时间升序（先来先叫）→ ticketId 升序兜底**。第三级保证**全序**——比较器返回 0 会让 PriorityQueue 认为元素相等，导致丢号。

### 3. 状态机

```
             takeNumber                    callNext(dept, roomNo)
          ───────────────► WAITING(排队中) ─────────► CALLING(就诊中)
               │              │  ▲                       │
               │              │  │ 过号重排               ├─ completeTicket → FINISHED
               │              │  └─（手动/20s超时自动）     └─ skipTicket → 过号重排
               │  cancelTicket
               ▼
          CANCELLED(已退号)
```

- **过号不是终态**：`requeueSkippedLocked()` 内 `skippedCount++`、`createTime = now`（比较器自然排到同优先级队尾）、`roomNo = 0`、释放诊室、重新入队——患者反复被叫不会丢号；
- **叫号超时自动过号**：`callNext` 后用 `ScheduledExecutorService`（单线程 "AutoSkip"）为该号安排 20 秒定时任务；医生及时完成/过号则 `cancelAutoSkip` 取消定时器；超时触发 `autoSkipIfStillCalling`——与医生操作竞争同一把队列锁、锁内校验 `state == CALLING`，状态机不会错乱；
- **锁的边界**：所有状态转换在 `synchronized(queue)` 内完成；定时器操作与广播在锁外执行，避免持锁做耗时/跨进程操作。

### 4. RemoteCallbackList 与客户端异常断开

- 多客户端回调用 `RemoteCallbackList<ITaskCallback>` 管理（子类化挂接 `onCallbackDied` 日志），注册时以客户端包名为 cookie；
- 客户端进程被杀 → Binder 死亡 → 回调**自动移除**，不持有失效 Binder，无内存泄漏；
- 广播协议：`beginBroadcast()/finishBroadcast()` 配对；广播期间不允许 unregister，死回调先记账、广播结束后统一移除；
- 广播为 oneway 异步事务，某个卡死的客户端不会拖垮对大屏/医生站的全量通知。

### 5. 查询返回快照

`queryTicket`/`queryQueue` 都在科室锁内做 `copy()` 返回快照，杜绝读到状态中间态；`queryQueue` 返回前把等待队列按叫号顺序排序（PriorityQueue 迭代本身无序）。

## 客户端设计（app 模块）

### 1. 三种角色（同一份代码，默认角色按 flavor 分工）

| 角色 | 默认 flavor | 功能 |
|---|---|---|
| 取号机 | TaskClient A | 选科室/填姓名（留空自动命名"患者A/B/C"）/勾优先号 → 取号；退号 |
| 医生工作站 | TaskClient B | **诊室选择**（1号/2号诊室）+ 双诊室状态显示；叫下一位 / 就诊完成 / 过号 |
| 叫号大屏 | TaskClient C | 各诊室当前就诊（多行）+ 排队列表（含"过号N次"标记），**TextToSpeech 语音播报**（可开关） |

顶部 RadioGroup 可随时切换任意角色，3 个 flavor 只是默认角色不同。

### 2. 语音播报（大屏）

- `TextToSpeech` 框架 API，初始化时校验中文语言包可用性，不可用则日志降级提示；
- 仅当角色为"叫号大屏"且开关开启时播报：`请 A零零三 号，患者A，到 内科 2号诊室 就诊`（号码数字转中文读法，TTS 朗读更自然）；
- `QUEUE_FLUSH` 保证连续叫号时新播报打断旧播报。

### 3. 连接管理（TaskSchedulerConnection）

- **显式 Intent**：`new Intent(action).setPackage(包名)`（Android 5.0+ 禁止隐式 Intent 绑定服务）；
- **三条断线路径全覆盖**：`onServiceDisconnected`（服务崩溃）、`onBindingDied`（API 26+）、`linkToDeath` 的 `binderDied`——统一走**指数退避重连**（1s→2s→4s→8s→10s 封顶，连上清零）；
- 正常 `unbindService` 不触发任何一条，重连只发生在真异常时；
- 生命周期：终端类应用连接**常驻**——`onCreate` 连接、`onDestroy` 断开，切后台不注销回调、持续接收广播（大屏回到前台时界面已是最新状态）；断线重连成功后从服务端 `queryQueue` 重新同步本地缓存。

### 4. 回调线程切换

`ITaskCallback.Stub` 方法运行在客户端 Binder 线程池，统一 `mainHandler.post()` 切回主线程再更新 UI；医生工作站的各诊室"当前就诊"由 CALLING 回调维护（key = 科室#诊室号），完成/过号按该缓存取 ticketId 操作。

## 验证清单

| 场景 | 操作 | 预期 |
|---|---|---|
| 3 客户端同时连接 | 同时打开 A/B/C | 均"● 已连接"；服务端 logcat 显示 registerCallback×3 |
| 优先号插队 | 取号时勾"优先号" | 医生叫号先叫优先号，大屏队列中排最前 |
| 双诊室并发 | 1号诊室叫号后再在 2号诊室叫号 | 两诊室同时显示"就诊中"，大屏"当前就诊"两行 |
| 诊室占用约束 | 就诊中再对同诊室叫号 | 被拒（-1），日志提示该诊室有患者 |
| 超时自动过号 | 叫号后 20 秒不做任何操作 | AutoSkip 线程自动重排，logcat 打印 autoSkip timeout，大屏"过号1次" |
| 过号重排 | 医生点"过号" | 号放回同优先级队尾，计数递增（第N次），再次叫号可叫到 |
| 就诊完成 | 医生点"就诊完成" | 号离队，诊室释放，可叫下一位 |
| 语音播报 | 大屏角色开播报，医生叫号 | 朗读"请A-00X号…就诊"；无中文语音包时日志降级提示 |
| 退号 | 对排队中的号退号 | 大屏队列立即移除，广播"已退号" |
| 客户端被杀 | `adb shell am force-stop com.tasklinker.clienta` | 服务端 logcat 打印 `client '...' died, callback removed automatically`；B/C 不受影响 |
| 服务端被杀 | `adb shell am force-stop com.tasklinker.server` | 3 个客户端"未连接"并按 1s/2s/4s/8s/10s 退避自动重连，重连后状态从服务端重新同步 |

## 设计决策与扩展点

- **从"任务执行"到"就诊过程"**：医院叫号是排队调度，原通用版的线程池执行被替换为**医生交互驱动**的状态机；优先级排序能力保留为"优先号插队"（比较器全序教训同样适用）。
- **超时用 ScheduledExecutorService 而非 Handler**：定时任务在服务端 Binder 进程内与 AIDL 调用竞争同一把队列锁，锁内校验状态即可保证正确性，无需 Handler/主线程参与。
- **过号重排而非终态**：真实医院患者过号后仍会回到队列；用 `skippedCount` 保留"被叫过几次"的事实，UI 据此展示，数据模型无需新增状态。
- **业务代码用 Java**（构建脚本为 Kotlin DSL）：AIDL 接口层与语言无关，Java 对工具链无额外要求。
- **共享库 AIDL 已验证可用**：`.aidl` 放在库模块即可，app 模块无需复制（复制会导致 D8 重复类报错），库编译出的 Stub 对消费方直接可见。
- **可扩展方向**：诊室数量按科室配置化、患者报到机（叫号后 N 分钟内报到，未报到自动过号）、过号次数上限（超过 M 次转人工）、取号时选择医生/诊室、服务端统计面板等。
