# TaskLinker —— 基于 AIDL 的跨应用医院叫号系统

一个完整的 Android 多进程 IPC 示例项目：**服务端 App** 在独立进程中提供医院叫号调度能力，**3 个客户端 App**（同一份代码的 3 个 flavor，可同时安装）分别扮演**取号机 / 医生工作站 / 叫号大屏**，通过 AIDL 实时交互：取号排队（优先号插队）、医生叫号、就诊完成/过号/退号，全部状态变化由服务端广播给所有已注册客户端，大屏实时刷新。

## 架构总览

```
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ TaskClient A │ │ TaskClient B │ │ TaskClient C │
│ 取号机       │ │ 医生工作站   │ │ 叫号大屏     │
│ 取号/退号    │ │ 叫号/完成/过号│ │ 当前叫号+队列│
└──────┬───────┘ └──────┬───────┘ └──────┬───────┘
       │  takeNumber    │  callNext      │  queryQueue（拉取）
       │  cancelTicket  │  complete/skip │
       └────────────────┼────────────────┘
              bindService + registerCallback(ITaskCallback)
                        ▼
        ┌──────────────────────────────────────────────┐
        │ 服务端 App 独立进程 com.tasklinker.server:scheduler│
        │  TaskSchedulerService                        │
        │  ┌─────────────────────────────────────────┐ │
        │  │ 内科 A：PriorityQueue 等待队列 + 当前就诊 │ │
        │  │ 外科 B：PriorityQueue 等待队列 + 当前就诊 │ │
        │  │ 儿科 C：PriorityQueue 等待队列 + 当前就诊 │ │
        │  │   优先号插队：优先级↓ → 取号时间↑ → id↑  │ │
        │  └─────────────────────────────────────────┘ │
        │  RemoteCallbackList<ITaskCallback>           │
        │  （死亡监听自动清理 + oneway 广播到全体客户端） │
        └──────────────────────────────────────────────┘
                        ▲
         onTicketStateChanged(ticket)：取号/叫号/完成/过号/退号
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
│           ├── TicketInfo.java       排队号 Parcelable（含 state 扩展字段）
│           ├── TicketState.java      状态常量：排队中/就诊中/完成/过号/退号
│           ├── TicketPriority.java   优先级：普通号/优先号
│           ├── HospitalDepartments.java  科室定义（内科A/外科B/儿科C）
│           └── TaskConstants.java    服务地址/权限/显式 Intent 构造
├── server/                       服务端 App（com.tasklinker.server）
│   └── src/main/
│       ├── AndroidManifest.xml       服务运行在独立进程 :scheduler + 自定义权限
│       └── java/com/tasklinker/server/
│           ├── TaskSchedulerService.java  ★核心：科室队列/叫号状态机/回调广播
│           └── MainActivity.java         演示界面（主进程）
└── app/                          客户端 App（3 个 flavor，可同时安装 3 个实例）
    └── src/main/
        ├── AndroidManifest.xml       使用权限 + <queries> 包可见性
        └── java/com/chihiro/tasklinker/
            ├── TaskSchedulerConnection.java  ★绑定/回调注册/死亡监听/指数退避重连
            ├── MainActivity.java            三角色界面（取号机/医生站/大屏）
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
   - **取号机**（默认是 A）：选"内科"，连点 3 次取号 → 拿到 A-001/A-002/A-003，第三个勾选"优先号"（模拟急诊/老人）；
   - **叫号大屏**（默认是 C）：选"内科"，无需任何操作——取号瞬间大屏已实时刷新出排队列表，A-003 优先号排在最前；
   - **医生工作站**（默认是 B）：选"内科"，点"叫下一位"→ 三台设备同时收到广播，大屏显示"当前叫号：A-003"（优先号插队成功！）；
   - 点"就诊完成" → 再叫下一位（A-001）→ 点"过号"（模拟患者未到）→ 日志与历史完整留痕；
   - 在取号机上对还在排队的 A-002 执行退号 → 大屏队列立即移除。

## AIDL 接口设计

### ITaskScheduler.aidl（同步调用，服务端 Binder 线程池并发执行）

```aidl
interface ITaskScheduler {
    int  takeNumber(in TicketInfo ticket);      // 取号，返回 ticketId
    int  callNext(String department);           // 叫下一位，返回被叫 ticketId；队列空或当前患者未处理完返回 -1
    boolean completeTicket(int ticketId);       // 就诊完成
    boolean skipTicket(int ticketId);           // 过号
    boolean cancelTicket(int ticketId);         // 退号（仅排队中可退）
    TicketInfo queryTicket(int ticketId);       // 单号查询快照
    List<TicketInfo> queryQueue(String department); // 科室队列：首位为正在就诊，其余按叫号顺序
    void registerCallback(ITaskCallback callback);
    void unregisterCallback(ITaskCallback callback);
}
```

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
| createTime | long | 取号时间戳，同优先级先来先叫 |
| state | int | **扩展字段**：排队中/就诊中/完成/过号/退号 |

## 服务端设计（TaskSchedulerService）

### 1. 科室队列模型

每个科室一个 `DepartmentQueue`：`PriorityQueue<TicketInfo> waiting`（等待队列）+ `TicketInfo current`（当前就诊位，一科同时只有一位患者就诊）+ `AtomicInteger` 序号计数器（生成 "A-001" 式号码）。

### 2. 叫号顺序 = 优先号插队

比较器三级排序：**优先级降序（优先号先叫）→ 取号时间升序（先来先叫）→ ticketId 升序兜底**。第三级保证**全序**——比较器返回 0 会让 PriorityQueue 认为元素相等，导致丢号，这是优先级队列的经典坑。

### 3. 状态机与锁

```
             takeNumber                    callNext
          ───────────────► WAITING(排队中) ─────────► CALLING(就诊中)
               │              │  │                        │  │
               │              │  │ cancelTicket(退号)      │  │ completeTicket(完成)
               │              ▼  ▼                        ▼  ▼
               │          CANCELLED                   FINISHED / SKIPPED(过号)
```

- **每个科室队列对象本身即锁**：取号入队、叫号出队、完成/过号/退号的状态转换全部在 `synchronized(queue)` 内完成，多 Binder 线程并发操作同一科室也不会破坏状态机；
- **广播一律在锁外执行**，避免持锁做 Binder 调用；
- 叫号约束：当前患者未完成/过号时 `callNext` 返回 -1，保证交互闭环（医生必须先处理完当前患者才能叫下一位）；
- 退号约束：只有 WAITING 可退；就诊中的号只能由医生完成或过号。

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
| 医生工作站 | TaskClient B | 叫下一位 / 就诊完成 / 过号，显示当前就诊患者 |
| 叫号大屏 | TaskClient C | 大字当前叫号 + 排队列表，**服务端每次广播自动实时刷新**（也可手动刷新/切换科室刷新） |

顶部 RadioGroup 可随时切换任意角色，3 个 flavor 只是默认角色不同。

### 2. 连接管理（TaskSchedulerConnection）

- **显式 Intent**：`new Intent(action).setPackage(包名)`（Android 5.0+ 禁止隐式 Intent 绑定服务）；
- **三条断线路径全覆盖**：`onServiceDisconnected`（服务崩溃）、`onBindingDied`（API 26+）、`linkToDeath` 的 `binderDied`——统一走**指数退避重连**（1s→2s→4s→8s→10s 封顶，连上清零）；
- 正常 `unbindService` 不触发任何一条，重连只发生在真异常时；
- 生命周期：终端类应用连接**常驻**——`onCreate` 连接、`onDestroy` 断开，切后台不注销回调、持续接收广播（大屏/取号机回到前台时界面已是最新状态）；断线重连成功后从服务端 `queryQueue` 重新同步本地缓存。

### 3. 回调线程切换

`ITaskCallback.Stub` 方法运行在客户端 Binder 线程池，统一 `mainHandler.post()` 切回主线程再更新 UI；医生工作站的"当前就诊"由 CALLING/FINISHED/SKIPPED 回调在本地维护，完成/过号按该缓存取 ticketId 操作。

## 验证清单

| 场景 | 操作 | 预期 |
|---|---|---|
| 3 客户端同时连接 | 同时打开 A/B/C | 均"● 已连接"；服务端 logcat 显示 registerCallback×3 |
| 取号 | 取号机连取 3 个号 | A-001/A-002/A-003，三个客户端同时收到"取号…排队中"日志 |
| 优先号插队 | 第 3 个勾"优先号"后取号 | 大屏队列中 A-003 排到最前，医生叫号先叫 A-003 |
| 叫号 | 医生点"叫下一位" | 大屏大字显示"当前叫号"，队列首位标记"就诊中" |
| 交互闭环 | 当前患者未处理完时再点"叫下一位" | 叫号被拒（返回 -1），日志提示先完成/过号 |
| 就诊完成/过号 | 医生分别操作 | 三端同步广播，大屏自动叫下一位的显示归零并刷新 |
| 退号 | 对排队中的号退号 | 大屏队列立即移除，广播"已退号" |
| 客户端被杀 | `adb shell am force-stop com.tasklinker.clienta` | 服务端 logcat 打印 `client '...' died, callback removed automatically`；B/C 不受影响 |
| 服务端被杀 | `adb shell am force-stop com.tasklinker.server` | 3 个客户端"未连接"并按 1s/2s/4s/8s/10s 退避自动重连 |
| 服务端未装/后装 | 只开客户端 | 绑定失败提示 + 自动重试，装好服务端后自动连上 |

## 设计决策与扩展点

- **从"任务执行"到"就诊过程"**：医院叫号是排队调度，原通用版的线程池执行被替换为**医生交互驱动**的状态机；优先级排序能力保留为"优先号插队"（比较器全序教训同样适用）。
- **业务代码用 Java**（构建脚本为 Kotlin DSL）：AIDL 接口层与语言无关，Java 对工具链无额外要求。
- **共享库 AIDL 已验证可用**：`.aidl` 放在库模块即可，app 模块无需复制（复制会导致 D8 重复类报错），库编译出的 Stub 对消费方直接可见。
- **可扩展方向**：叫号后超时自动过号（用 ScheduledExecutorService 定时器）、多诊室（每科室多窗口）、过号重排（过号患者回到队尾）、患者报到机（叫号后 3 分钟内报到）、叫号语音播报、服务端统计面板等。
