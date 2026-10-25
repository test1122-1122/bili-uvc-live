# B 站直播协议与实现说明

本项目通过 **B 站网页/直播中心接口** 完成扫码登录、读取直播间、申请开播、停播，并把开播接口返回的 RTMP 地址交给自研 RTMP 客户端推流。本文记录实测可用的接口、参数与返回结构，便于后续维护。

> 参考实现：`ref/bilibili-stream-for-obs-main/src/bilibili_api.cpp`（OBS 插件，1.3.3，实测验证了扫码登录、开播、人脸验证、停播流程）。
>
> 实测时间：**2026-10-25（Asia/Shanghai）**，只做未登录只读请求（二维码生成、分区列表、直播版本信息），未使用任何账号登录。B 站接口可能随时变更。

## 1. 通用

- 协议：HTTPS
- 公共请求头（桌面 Chrome UA）：

```
Accept: application/json, text/plain, */*
Accept-Language: zh-CN,zh;q=0.9,en;q=0.8
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
Origin: https://link.bilibili.com
Referer: https://link.bilibili.com/p/center/index
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36
Cookie: <SESSDATA=...; bili_jct=...; DedeUserID=...; ...>
```

- 成功响应形如 `{"code":0,"message":"OK","data":{...}}`；`code != 0` 时 `message` 为错误描述。
- 需要登录的接口必须带 Cookie；写操作使用 `csrf`/`csrf_token` = Cookie 中的 `bili_jct`。
- 登录态判定：`GET https://api.bilibili.com/x/web-interface/nav` → `data.isLogin`；`code=-101` 表示未登录/失效。

## 2. 扫码登录

### 2.1 生成二维码

`GET https://passport.bilibili.com/x/passport-login/web/qrcode/generate`

实测返回（2026-10-25）：

```json
{
  "code": 0,
  "message": "OK",
  "ttl": 1,
  "data": {
    "url": "https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1&callback=close&qrcode_key=c0a68587831f259dc132da2381c9a1c8&from=",
    "qrcode_key": "c0a68587831f259dc132da2381c9a1c8"
  }
}
```

App 把 `data.url` 生成二维码，展示给用户用 B 站手机客户端扫描。

### 2.2 轮询扫码状态

`GET https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=<key>`

`data.code`：

| code | 含义 | App 行为 |
|---|---|---|
| 0 | 登录成功 | 从响应 `Set-Cookie` 收集 `SESSDATA`、`bili_jct`、`DedeUserID` 等；必要时 GET `data.url` 补票 |
| 86038 | 二维码已过期 | 重新生成二维码 |
| 86090 | 已扫码，待确认 | 继续轮询 |
| 86101 | 等待扫码 | 继续轮询 |

成功时响应体 `data.url` 是登录跳转地址；HTTP 响应的 `Set-Cookie` 通常已包含完整凭据。实现（`bili/BiliApi.kt`）会合并两种来源，并校验至少包含 `SESSDATA`、`bili_jct`、`DedeUserID`。

## 3. 用户与直播间

| 用途 | 请求 | 关键返回 |
|---|---|---|
| 登录状态 | `GET https://api.bilibili.com/x/web-interface/nav` | `data.isLogin`、`data.mid`、`data.uname` |
| 房间号 | `GET https://api.live.bilibili.com/room/v2/Room/room_id_by_uid?uid=<uid>` | `data.room_id`（0 = 未开通直播间） |
| 直播间信息 | `GET https://api.live.bilibili.com/xlive/web-room/v1/index/getRoomBaseInfo?req_biz=web_room_componet&room_ids=<roomId>` | `data.by_room_ids[roomId]`：`title`、`parent_area_id`、`area_id`、`parent_area_name`、`area_name`、`live_status` |
| 直播状态 | `GET https://api.live.bilibili.com/room/v1/Room/getRoomInfoOld?mid=<uid>` | `data.liveStatus`（0 未开播 / 1 直播中 / 2 轮播） |
| 分区列表 | `GET https://api.live.bilibili.com/room/v1/Area/getList` | `data[]`：`id`、`name`、`list[]`（子分区 `id`/`name`） |
| 更新标题 | `POST https://api.live.bilibili.com/room/v1/Room/update` | 表单：`room_id`、`platform=pc_link`、`title`、`csrf_token`、`csrf` |

分区列表实测返回正常（`code:0`，含「网游/英雄联盟」等）。

**实测补充（2026-10-25，用编译后的 `BiliApi` 只读调用）**：

| 调用 | 结果 |
|---|---|
| `qrcode/generate` | HTTP 200、`code=0`；`data.url` 已变为 `https://account.bilibili.com/h5/account-h5/auth/scan-web?...`（不是 passport 域） |
| `qrcode/poll`（未扫码） | `data.code=86101` → `Pending` |
| `nav`（伪造 Cookie） | `code=-101` → 未登录 |
| `Area/getList` | 12 个一级分区；**子分区 `id` 实际是字符串**（如 `"86"`），解析需兼容字符串/数字 |
| `room_id_by_uid?uid=1` | `data.room_id=553241` |
| `getRoomBaseInfo?room_ids=553241` | 返回 `by_room_ids`，标题可读（示例房间 `area_id=0`，说明部分房间分区字段可能为空） |
| `getRoomInfoOld?mid=2` | `data.liveStatus=0`（未开播） |

## 4. App 签名（开播接口必需）

`appkey = aae92bc66f3edfab`，`appsecret = af125a0d5279fd576c1b4418a3e8276d`

1. 所有参数（含 `appkey`）按 key 字典序排序；
2. 拼成 `k1=v1&k2=v2...`，末尾直接追加密钥 → `...&appkey=...<appsecret>`；
3. 计算 MD5 十六进制小写 = `sign`；
4. 最终请求体/查询串追加 `&sign=<sign>`（`appkey` 也在其中）。

实测（2026-10-25）：

```
GET https://api.live.bilibili.com/xlive/app-blink/v1/liveVersionInfo/getHomePageLiveVersion?system_version=2&ts=<epoch>&appkey=<appkey>&sign=<sign>
→ 200 {"code":0,"data":{"curr_version":"8.11.1.11202","build":11202, ...}}
```

说明签名算法正确，且开播接口要求先取「当前直播姬版本号/构建号」。

> 实测 `build=11202`、`curr_version=8.11.1.11202`（2026-10-25）。App 每次开播都会重新取版本号，不硬编码。

## 5. 开播

### 5.1 取版本信息

`GET .../xlive/app-blink/v1/liveVersionInfo/getHomePageLiveVersion`（参数 + 签名见上），读取 `data.build`、`data.curr_version`。

### 5.2 申请开播

`POST https://api.live.bilibili.com/room/v1/Room/startLive`

表单参数（全部参与签名）：

```
room_id=<房间号>
platform=pc_link
area_v2=<子分区 id>
backup_stream=0
csrf_token=<bili_jct>
csrf=<bili_jct>
build=<liveVersionInfo.data.build>
version=<liveVersionInfo.data.curr_version>
ts=<epoch 秒>
appkey=<appkey>
sign=<sign>
```

成功（`code=0`）：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "rtmp": {
      "addr": "rtmp://live-push.bilivideo.com/live-bvc/",
      "code": "?streamname=live_xxx_xxx&key=xxxxxxxx&schedule=rtmp&pflag=1"
    }
  }
}
```

推流 URL = `addr` + `code`，即：

```
rtmp://live-push.bilivideo.com/live-bvc/?streamname=live_xxx_xxx&key=xxxxxxxx&schedule=rtmp&pflag=1
```

RTMP 语义（与 OBS「服务器地址 + 串流密钥」一致）：

- `app` = `live-bvc`
- `tcUrl` = `rtmp://live-push.bilivideo.com/live-bvc`
- `publish` 命令的 stream name = `?streamname=...&key=...`（**保留前导 `?`**）
- 推流协议：RTMP + FLV 封装，H.264（AVC）+ AAC

### 5.3 人脸验证 / 风控

开播失败且返回以下 `code` 时，需要用户在 B 站手机客户端完成验证：

| code | 行为 |
|---|---|
| 60024 / 60028 | 响应 `data.qr` 是人脸验证二维码内容，App 展示二维码并轮询验证状态 |
| 60043 | 无 `data.qr`；使用 `https://www.bilibili.com/blackboard/live/face-auth-middle.html?source_event=400&mid=<uid>` 作为二维码内容 |
| 其它 | 将 `message` 展示给用户 |

验证状态轮询：

`POST https://api.live.bilibili.com/xlive/app-blink/v1/preLive/IsUserIdentifiedByFaceAuth`

```
room_id=<roomId>&face_auth_code=<code>&csrf_token=<bili_jct>&csrf=<bili_jct>
```

`data.is_identified` 为真（`true` / `1`）后，重新调用 `startLive`。若仍返回人脸验证，继续等待（可手动重试）。

> 说明：60043 在新版直播中心走官方风控 SDK（`x-bili-gaia-vtoken` 等），OBS 插件也只恢复旧版手机中间页，不保证所有账号/场景可用。详见参考仓库 `docs/face-auth-contract.md`。

## 6. 停播

`POST https://api.live.bilibili.com/room/v1/Room/stopLive`

```
room_id=<roomId>&platform=pc_link&csrf_token=<bili_jct>&csrf=<bili_jct>
```

App 在「用户点停止」和「退到后台/摄像头断开」时会调用。若推流已断开但停播失败，可重试或到 B 站直播中心手动关闭。

## 7. RTMP / FLV 实现要点

自研 `rtmp/` 包（`RtmpPublisher`、`RtmpProtocol`、`H264AnnexB`、`AacConfig`、`Amf0`）：

1. **握手**：C0/C1/C2 simple handshake。
2. **命令**：AMF0 `connect`（`app`、`type=nonprivate`、`tcUrl`、`flashVer`、`fpad=false`、`audioCodecs=3575`、`videoCodecs=252`、`videoFunction=1`、`objectEncoding=0`）→ `createStream` → `publish(streamName, "live")`；等待 `_result`/`onStatus`。
3. **控制**：发送 `Window Acknowledgement Size`、`Set Peer Bandwidth`；设置 out chunk size = 4096；处理服务端 `Set Chunk Size`、`Acknowledgement`、`User Control(PingRequest→Pong)`。
4. **FLV 媒体 tag**：
   - 视频 sequence header：`0x17 0x00 0x000000` + `AVCDecoderConfigurationRecord`（从 SPS/PPS 构造）
   - 视频帧：`0x17`（关键帧）/`0x27`（非关键帧）`0x01 0x000000` + `[4 字节长度 + NAL]*`（AVCC 格式）
   - 音频 sequence header：`0xAF 0x00` + `AudioSpecificConfig`（44.1kHz / 2ch / AAC-LC）
   - 音频帧：`0xAF 0x01` + 原始 AAC 帧（无 ADTS）
   - 首包前发送 `@setDataFrame` `onMetaData`（宽/高/fps/码率）
5. **时间戳**：RTMP 消息时间戳 = 毫秒（相对首帧），音视频各自单调；FLV tag 也是毫秒。
6. **编码**：视频 MediaCodec `video/avc`（Annex-B 输出，含 SPS/PPS codec-config）→ Publisher 自动提取 SPS/PPS；音频 MediaCodec `audio/mp4a-latm` 输出 raw AAC + csd-0（ASC）。
7. **只支持 `rtmp://`**（TCP 1935/自定义端口）；`rtmps://`（TLS）解析可得但 publisher 明确拒绝。B 站当前推流地址为 `rtmp://`，不受影响。
8. **配置帧时序**：MediaCodec 在编码器启动时（通常早于 RTMP publish 成功）就输出 SPS/PPS codec-config；Publisher 必须在未发布状态也缓存参数集，并在进入 PUBLISHING 后首个画面帧前补发 AVC sequence header（已有集成测试覆盖）。
9. **A/V 时间戳**：两个编码器都使用 `System.nanoTime()/1000` 同一基准；Publisher 以首个媒体帧为 0 点、每轨单调化，容忍少量乱序。

## 8. 安全与合规

- 登录 Cookie 仅保存在本机 `SharedPreferences`（`SessionStore`），不做上传；退出登录即清除。
- 项目不包含任何账号、Cookie 或私钥。仓库内 `keystore/debug.keystore` 是测试签名，不用于生产。
- 开播/人脸验证接口为非官方接口，存在失效与风控风险；请遵守 B 站直播规范，风险自负。
