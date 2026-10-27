# Portal2026v2 服务状态探针

这是供 Android 13 设备使用的 `app_process` 工具，不安装应用，也不读取 GPS、网络定位或实时位置。它以已有的 `adb shell` 身份访问 `portal2026v2` 的受保护命令通道；状态查询是只读的，显式的 `--stop-verified` 会停止固定点模拟。

此工具只确认模拟服务及固定点配置。`app_process` 没有普通 Android 应用的完整启动和权限归属环境，不能用于证明普通应用获取位置的结果；普通应用的接口验证由 `tools/location-probe-app` 执行。

## 构建

需要 JDK、Android SDK Platform 35 和 Build Tools 35.0.0。每次构建写入 `build/run-*` 的新目录，不删除旧结果：

```sh
export JAVA_HOME="/path/to/jdk"
export ANDROID_HOME="/path/to/android-sdk"
./tools/location-probe/build.sh
```

脚本输出具体的 `classes.dex` 路径。需要测试时，把这个文件复制到已授权设备的临时目录，再使用 `adb shell` 运行：

```sh
adb push <build-output>/classes.dex /data/local/tmp/portal-ipc-probe.dex
adb shell 'CLASSPATH=/data/local/tmp/portal-ipc-probe.dex app_process64 /system/bin moe.fuqiuluo.locationprobe.LocationProbe --ipc-status'
adb shell 'CLASSPATH=/data/local/tmp/portal-ipc-probe.dex app_process64 /system/bin moe.fuqiuluo.locationprobe.LocationProbe --ipc-verify 0 0'
```

`--ipc-status` 检查是否启用固定位置，以及服务回读的配置是否有效且前后一致；`--ipc-verify <纬度> <经度>` 还检查配置是否等于指定测试点。两种模式都不会输出或保存坐标或密钥。

`--fused-status` 只回读融合定位开关，输出 `FUSED_ALLOWED`、`FUSED_BLOCKED` 或 `UNAVAILABLE`。`--stop-verified <纬度> <经度>` 先确认当前固定配置与给定测试点一致，才发送停止命令，再回读服务状态；输出 `STOPPED`、`STOP_NOT_ATTEMPTED`、`STOP_FAILED` 或 `UNAVAILABLE`。停止命令会改变模拟运行状态。

| 输出 | 含义 | 退出码 |
| --- | --- | ---: |
| `IPC_READY` | 启用状态和固定点配置通过对应检查 | 0 |
| `IPC_NOT_READY` | 服务可响应，但固定点未启用、配置无效或与指定测试点不符 | 10 |
| `UNAVAILABLE` | shell 身份、上下文、参数或 IPC 不可用 | 11 |

成功只证明当前控制通道的回读，不能证明所有定位来源或其他应用都受到保护。普通应用实测前的 IPC 检查与随后的位置读取并非原子操作。
