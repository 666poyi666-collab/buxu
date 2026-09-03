# OWW221 手表运动逆向初步报告

## 设备画像

- 型号：`OWW221`
- Android：`11` / API `30`
- 架构：`armeabi-v7a`
- 构建：`OPPO/OWW221/OWW221:11/RKQ1.220916.001.11_A.281.260807001007/01:user/release-keys`
- ADB：USB 序列 `2e28bb17`

## 已提取组件

提取目录：`.work/watch-reverse/`。原始 APK 未修改，SHA-256 以本次提取文件为准。

| 包 | 版本 | 角色 |
|---|---|---|
| `com.heytap.wearable.health` | `4.1.3_3a3ac6f_260727` | HealthKit、运动服务、数据存储 |
| `com.heytap.wearable.sports` | `4.0.79.1_80c9669_260702` | 系统运动 UI/生命周期 |
| `com.heytap.wearable.research` | `2.0.58.1_e9ea3a3_260701` | 工程/研究测试入口 |
| `com.heytap.wearable.mculinkservice` | `1.1.19` | MCU 传输与加密 |
| `com.heytap.wearable.oms.service` | `2.0.4_d239fed_250306` | WearEngine/设备互联 |

## 关键接口

`com.heytap.wearable.health` 注册了以下服务：

- `heytap.wearable.intent.action.BIND_EXERCISE_SERVICE` → `.healthkit.exercise.ExerciseService`
- `heytap.wearable.intent.action.BIND_STORE_SERVICE` → `.healthkit.store.StoreApiService`
- `heytap.wearable.intent.action.BIND_CLIENT_SERVICE` → `com.oplus.wearable.healthkit.impl.VersionApiService`
- `heytap.wearable.intent.action.BIND_HEALTH` → `.service.HealthService`
- `heytap.wearable.intent.action.BIND_MEASURE_SERVICE` / `BIND_PASSIVE_SERVICE`

DEX 中确认的协议对象包括：

- `com.oplus.wearable.healthkit.exercise.ExerciseClient`
- `com.oplus.wearable.healthkit.store.IStoreApiService`
- `com.heytap.wearable.health.Exercise$StartExerciseRequest`
- `Exercise$ExerciseActionRequest`、`Exercise$ExerciseStatsResponse`
- `Exercise$ExerciseDataAvailable`、`Exercise$LastEndDataReq`
- `Sports$StartSportData`、`Sports$SportRecordReport`

运动状态常量已恢复：`EXERCISE_ACTION_START/PAUSE/RESUME/END`、`EXERCISE_STATUS`、`DATA_TYPE_STEPS`，以及 `startExercise`、`updateExerciseState`、`endExercise`、`readRecords`、`insertRecords` 等方法名。

## 权限与测试入口

- `heytap.wearable.permission.health.BIND_EXERCISE_SERVICE`、`BIND_STORE_SERVICE` 为普通绑定权限。
- `heytap.wearable.permission.health.BIND_TEST_TOOL`、`BIND_HEALTH`、`BIND_CONN` 为签名权限。
- `com.heytap.wearable.research` 已声明并获得 `BIND_TEST_TOOL`、`BINDER_PROVIDER`、`BIND_HEALTH`，启动 Activity 为 `.pwv.ftmain.PwvMainActivity`。
- 手表当前 `OUTDOOR_RUN` 能力为空，说明“服务存在”不等于该固件开放写入能力。

## 结论

真正的系统运动记录路径是：

`Sports UI → ExerciseService/HealthKit Binder → MCU link → OEM Health DB → 手机同步`

应用私有 `files/workouts` 与该路径无关。下一步应对研究包和 HealthKit 做动态 Binder 跟踪，先调用只读 capability/version，再在测试项目授权下观察 `StartExerciseRequest` 的字段与 MCU 回包；不能把数据库副本或未授权 Provider 写入当成系统记录。

## 复现命令

```powershell
$adb = 'C:\Users\16408\Desktop\开发\platform-tools\adb.exe'
& $adb -s 2e28bb17 shell pm path com.heytap.wearable.health
& $adb -s 2e28bb17 shell dumpsys package com.heytap.wearable.health
& $adb -s 2e28bb17 shell am start -n com.heytap.wearable.research/.pwv.ftmain.PwvMainActivity
```

