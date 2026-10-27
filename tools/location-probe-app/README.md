# Location Probe App

这是一个独立、无启动器图标的普通 Android 探针包：`moe.fuqiuluo.locationprobe`。
Activity 为 `exported=true`，只接受指定 action、预期值和 confirmation extra；
默认启动路径不创建 `LocationManager`，也不调用任何定位读取 API。

## 构建

构建脚本不下载依赖、不删除文件，也不会覆盖已有输出。每次构建都写入新的
`build/run-*` 目录。它要求 Android SDK platform 35、
build-tools 35、JDK 17、`aapt2`/`d8`/`apksigner` 和已有 debug keystore。
路径均可显式传入：

```sh
cd tools/location-probe-app
# Set these four variables to the local SDK/build-tools/JDK17/debug-keystore paths.
ANDROID_SDK_ROOT="$SDK_ROOT" \
ANDROID_BUILD_TOOLS_DIR="$BUILD_TOOLS_ROOT" \
JAVA_HOME_17="$JDK17_ROOT" \
DEBUG_KEYSTORE="$DEBUG_KEYSTORE_PATH" \
./build.sh
```

也可以分别设置 `ANDROID_PLATFORM_JAR`、`AAPT2`、`D8`、`APKSIGNER`、
`JAVAC`。脚本最后打印签名 APK 和本次唯一输出目录。

## 安全运行方式

探针不会自动连接设备。本地构建后，只有在明确授权、确认设备和模块状态后，
才由操作者自行执行下面的显式命令；本项目不会替代执行这些命令：

```sh
APK_PATH=build/run-XXXXXXXX/location-probe.apk  # use the exact path printed by build.sh
adb install -r "$APK_PATH"
adb shell am start -W \
  -a moe.fuqiuluo.locationprobe.action.RUN_CONFIRMED_TEST \
  -n moe.fuqiuluo.locationprobe/.ProbeActivity \
  --es probe_command "TEST 1,2" \
  --es probe_expected "1,2" \
  --es probe_confirmation "IPC_READY_1,2"
adb logcat -s LocationProbe:I '*:S'
```

上面的 `probe_confirmation` 不是授权凭据；宿主编排器只能在已有 shell probe
确认 `IPC_READY` 且固定配置为 `1,2` 后才传入它。不要在未完成预检时手动伪造
confirmation。

若需要检查权限拒绝路径，使用：

```sh
adb shell am start -W \
  -a moe.fuqiuluo.locationprobe.action.RUN_CONFIRMED_TEST \
  -n moe.fuqiuluo.locationprobe/.ProbeActivity \
  --es probe_command "TEST_DENIED" \
  --es probe_expected "1,2" \
  --es probe_confirmation "IPC_READY_1,2"
```

`TEST_DENIED` 也必须先经过宿主 shell probe 的固定点预检，之后只调用一次
`getLastKnownLocation(GPS_PROVIDER)`：捕获 `SecurityException` 记录
`RESULT DENIED`，得到非空结果记录 `RESULT NOT_DENIED`，null 记录
`RESULT UNAVAILABLE`。它绝不输出坐标。

## Fused 单次 current 测试

在同一固定点 gate 下，`TEST_FUSED_CURRENT` 只调用一次公开的
`getCurrentLocation("fused", ...)`，等待上限为 20 秒：

```sh
adb shell am start -W \
  -a moe.fuqiuluo.locationprobe.action.RUN_CONFIRMED_TEST \
  -n moe.fuqiuluo.locationprobe/.ProbeActivity \
  --es probe_command "TEST_FUSED_CURRENT" \
  --es probe_expected "1,2" \
  --es probe_confirmation "IPC_READY_1,2"
```

日志只有一条 `RESULT fused current MATCH`、`RESULT fused current MISMATCH` 或
`RESULT fused current UNAVAILABLE`。

## 更新回调测试

在同一 action、`probe_expected=1,2`、`probe_confirmation=IPC_READY_1,2` 门槛下，
使用：

```sh
adb shell am start -W \
  -a moe.fuqiuluo.locationprobe.action.RUN_CONFIRMED_TEST \
  -n moe.fuqiuluo.locationprobe/.ProbeActivity \
  --es probe_command "TEST_UPDATES" \
  --es probe_expected "1,2" \
  --es probe_confirmation "IPC_READY_1,2"
```

它分别检查 GPS、network、fused 的 last 和 `requestLocationUpdates`：每个 listener
更新等待最多 8 秒，结果只会是 `MATCH`、`MISMATCH` 或 `UNAVAILABLE`，并按
`RESULT <provider> <last|updates> <status>` 记录；最后输出一个聚合 `RESULT`。
每个 listener 都在 `finally` 中注销，回调 HandlerThread 也会关闭。等待发生在
后台 worker，不阻塞 Activity 主线程。

## 无权限 listener 测试

```sh
adb shell am start -W \
  -a moe.fuqiuluo.locationprobe.action.RUN_CONFIRMED_TEST \
  -n moe.fuqiuluo.locationprobe/.ProbeActivity \
  --es probe_command "TEST_DENIED_LISTENER" \
  --es probe_expected "1,2" \
  --es probe_confirmation "IPC_READY_1,2"
```

该模式不预先检查权限，而是尝试注册 GPS listener，并观察随后 5 秒：

- `SecurityException` 且无回调：`RESULT DENIED_NO_CALLBACK`；
- `SecurityException` 但出现回调：`RESULT DENIED_WITH_CALLBACK`；
- 注册未抛 `SecurityException`：`RESULT NOT_DENIED`；
- 其他运行时失败或线程中断：`RESULT UNAVAILABLE`。

listener 始终在 `finally` 中注销，绝不输出坐标。

## Fused 无权限 listener 测试

```sh
adb shell am start -W \
  -a moe.fuqiuluo.locationprobe.action.RUN_CONFIRMED_TEST \
  -n moe.fuqiuluo.locationprobe/.ProbeActivity \
  --es probe_command "TEST_DENIED_FUSED" \
  --es probe_expected "1,2" \
  --es probe_confirmation "IPC_READY_1,2"
```

该模式尝试注册 `fused` listener，等待 5 秒并输出且仅输出以下之一：
`RESULT FUSED_DENIED_NO_CALLBACK`、`RESULT FUSED_DENIED_WITH_CALLBACK`、
`RESULT FUSED_NOT_DENIED` 或 `RESULT UNAVAILABLE`。listener 始终在
`finally` 中注销。

## 停止模拟后的持续监听测试

`TEST_STOP_MONITOR` 仍需要同一固定点 gate。它注册一个仅在本次 Activity 运行期间
存在的动态 receiver，并以 `android.permission.DUMP` 保护；manifest 不注册该 receiver。
它在 GPS listener 收到两次内存比较为 `1,2` 的回调后输出：
`RESULT STOP_MONITOR_READY`。准备阶段上限为 20 秒，超时输出 `RESULT UNAVAILABLE`。

```sh
adb shell am start -W \
  -a moe.fuqiuluo.locationprobe.action.RUN_CONFIRMED_TEST \
  -n moe.fuqiuluo.locationprobe/.ProbeActivity \
  --es probe_command "TEST_STOP_MONITOR" \
  --es probe_expected "1,2" \
  --es probe_confirmation "IPC_READY_1,2"
```

宿主必须先看到 `RESULT STOP_MONITOR_READY`，再确认停止模拟成功；确认成功后发送下面的
指定 action 作为 marker（使用包目标，shell/授权 host 需具备 `android.permission.DUMP`）：

```sh
adb shell am broadcast \
  -a moe.fuqiuluo.locationprobe.action.STOP_SIMULATION_SUCCESS \
  --receiver-registered-only \
  -p moe.fuqiuluo.locationprobe
```

探针等待 marker 最多 60 秒；收到后继续观察 6 秒。若期间又收到 `1,2`，输出
`RESULT STOP_FAKE_AFTER`，否则输出 `RESULT STOP_NO_FAKE_AFTER`。marker 超时或线程
中断输出 `RESULT UNAVAILABLE`。listener、receiver、Handler 和 HandlerThread 均在
`finally` 中注销/关闭；action 不携带位置数据。

## 测试语义与隐私边界

宿主编排器必须先使用已有 shell probe，通过受保护的 `portal2026v2` IPC 完成
`exchange_key`、`is_start`、`get_fixed_config` 校验，并确认输出 `IPC_READY`、
固定模式和有限配置的纬度/经度为 `1,2`。普通探针故意不执行这一步，因为其独立
包名不在后端 `portal2026v2` 的调用者 allowlist 中；它不会绕过该授权边界。

只有预检通过后，宿主才可以用指定 action、`probe_expected=1,2` 和
`probe_confirmation=IPC_READY_1,2` 触发 `TEST 1,2`、`TEST_UPDATES`、
`TEST_DENIED_LISTENER`、`TEST_FUSED_CURRENT`、`TEST_DENIED_FUSED` 或
`TEST_STOP_MONITOR`。探针随后按对应模式检查公开 LocationManager API；
current 的单次超时为 20 秒，普通 update callback 为 8 秒，停止监听 marker
阶段另有 60 秒上限和 marker 后 6 秒观察窗口：

- 通过宿主预检并收到确认后才调用定位读取 API；
- 日志只包含各模式约定的 `RESULT` 状态及 provider/source 状态，不包含任何坐标；
  null 或超时不能报告 MATCH；
- 所有坐标只用于内存中的比较，绝不写日志、Intent、文件或持久化状态；探针不
  保存 IPC Bundle，也不声明网络权限。

这个 shell 预检与随后普通 app 读取之间不是原子操作：固定状态可能在两步之间
改变。因此即使最终日志为 MATCH，也不能证明所有应用、所有时间窗口或所有厂商
私有路径都绝对不会泄露真实位置；它只报告该次调用观察到的结果。
