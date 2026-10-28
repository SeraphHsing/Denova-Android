# P4-C · 资料库材料与媒体（LOR-002）真机证据

- 日期：2026-10-28
- 设备：Xiaomi 14 Pro（`135babde`，Android 16）
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3 / `85ec97c`）
- 项目：`project-9a1ce5282872a3784807476ba907fedb`（`p4c-lore-apply`，取证后已移除书籍记录）；语音试听另用临时书 `p4c-audio-test`（取证后同样移除）
- 构建：JVM 73 套件 / 511 用例 / 0 失败 + `lintDebug` + `assembleDebug` 通过

## 1. 步骤与实测结果

| 步骤 | 实测结果 |
|---|---|
| 上传材料 | 条目 `p4c-item-a` →「上传材料」→ 系统选择器选 `p4c-material.png`（70B）→ `Lore: material uploaded item=p4c-item-a name=p4c-material.png`，界面「材料（1）」并显示缩略图 |
| 上传读回 | `GET .../book/lore/assets` → `asset_2cfbf22d-…`，`path=assets/lore/c56f8ee5-….png`、`mime_type=image/png`、`size_bytes=70`、`source.kind=upload`；条目 `materials.entries[0].asset_id` 指向同一资产 |
| 素材空态 | 未上传前点「从项目素材」→ 对话框显示「没有可用的项目素材」 |
| 关联既有素材 | 条目 `p4c-class-alice` →「从项目素材」→ 选 `p4c-material.png` → `Lore: material linked item=p4c-class-alice asset=asset_2cfbf22d-…`；服务端该条目 `entries=[{asset_id 同一值}]`、`resolved_materials[0].path` 与上传时一致（未复制、未重复上传） |
| 生成语音 | 对话框预填条目名与摘要 → 确认 → 进度行「正在生成语音…」→ 约 8s → `Lore: generated speech item=p4c-class-alice`，提示「语音已生成」，材料 1→2 |
| 语音读回 | `resolved_materials` 新增 `audio/mpeg` **82,560B**、`source.kind=generated`、`path=assets/lore/9c6d51c6-….mp3`；`materials.entries` 写入 `name=p4c-class-alice` 与朗读文本 description |
| 生成配图（图像 Agent） | 「生成配图」→ 方式默认「图像 Agent」→ 附加要求 `red-panda-knight` → 确认 → 进度行「正在生成配图…」→ 约 100s → `Lore: generated image item=p4c-class-alice`，提示「配图已更新」 |
| 配图读回 | 新增 `image/png` **2,621,986B**、`source.kind=generated`、`path=assets/lore/c4553aa9-….png`；材料 3 |
| 设为封面 | 对该图片点「设为封面」→ `materials.cover_asset_id=asset_ef6cbdc5-…`；服务端派生 `image.image_path` 指向该 png，编辑器顶部出现「封面配图」 |
| 取消 | 生成进行中点「取消」→ `Lore: generation cancelled by user`，提示「已在本机取消，服务端可能仍会完成。」，进度行消失、按钮恢复可用 |
| 错误呈现 | 图像 Agent 仍被上一次任务占用时，界面原样显示服务端原因「资料库操作失败: agent session is busy」（不吞错、不误报成功） |
| 语音试听 | 临时书 `p4c-audio-test` 的条目 → 材料行「播放」→ `Lore: audio play asset=asset_87385315-… bytes=26496 duration=1656ms`；播放结束自动复位为「播放」，无错误提示 |
| 材料改名 | 临时书 `p4c-media-test` 的条目 → 材料行「改名」→ 名称改为 `renamed-mat` → 服务端 `materials.entries[0].name=renamed-mat`，界面同步 |
| 远程图片 | 同条目「＋材料」→「远程图片」→ `https://www.baidu.com/img/flexible/logo/pc/result.png` + 名称 `remote-logo` → `Lore: remote material item=…`；服务端读回 `name=remote-logo`、`source.kind=web`、`source.url=…`、`mime_type=image/*`、`path=null`（未勾选本地副本时不落盘） |

截图：`shots/130-lore-generate-progress.png`（进度行）、`131-lore-generate-running.png`（生成中 + 取消）、`134-lore-materials.png`（材料缩略图与操作）、`135-lore-audio-playing.png`（语音材料）。

## 2. 同轮修复

1. **关联素材的操作名是 `link` 不是 `add`**：服务端 `Store.MutateMaterial` 只接受 `link / update / remove / cover / cover_if_missing`，`add` 会被判 400 `unknown material operation: add`（真机首次点击即复现）。改为 `op=link` 后关联成功。
2. **图像 Agent 必须带 `command_id`**：`mode=agent` 时服务端返回 400 `agent command_id is required`。按 Web `createAgentCommandID()` 的口径，每次提交生成一个 uuid 作为该次逻辑命令的幂等键。
3. **慢接口读超时太短**：配图 / 语音 / 索引预览 / 分类预览都要过模型，30s 读超时会在正常等待中报 `SocketTimeoutException`（此前表现为「分类失败」无原因）。新增 `slowHttpClient`（readTimeout 180s / callTimeout 240s）只用于这五个端点。
4. **媒体回包会覆盖正在编辑的正文**：上传/生成完成后原实现用服务端条目重建表单，把用户正在输入的正文抹掉；改为只更新列表里的条目（基线 `LoreDetailTabs.applyMedia` 只合并 image/materials）。
5. **材料没有预览**：新增受保护图片缩略图（Coil + 会话 Cookie，封面与材料共用）与语音试听（带会话取字节 → 本地 `MediaPlayer`，因 MediaPlayer 拿不到会话 Cookie）。

## 3. 补齐的能力（第二轮）

- **材料改名 / 改描述**：`op=update`，材料行「改名」入口，真机读回 `name=renamed-mat`。
- **远程图片材料**：`op=remote`（可勾「同时保存本地副本」→ `save_locally`），走慢客户端；真机读回 `source.kind=web` 与 `source.url`。
- **材料来源菜单**：上传 / 从项目素材 / 远程图片收进「＋ 材料」，避免手机上按钮并排挤掉可点区域；材料行动作改为「播放（仅音频）/ 封面 / 改名 / 删除」。

## 4. 缺口（基线有、本行未要求）

- 多选批量上传与逐文件失败汇总（基线 `LoreMaterialsPanel` 一次可选多文件）；
- 图片材料的全屏查看器与 `localize`（把远程图片保存为本地副本）单独入口。
