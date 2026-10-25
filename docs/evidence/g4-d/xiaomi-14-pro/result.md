# G4-d 跨包（升级安装）· 小米 14 Pro 真机

- 设备：Xiaomi 14 Pro（`135babde`），MIUI，1440×3200
- 服务器：`http://192.168.2.102:18081`（后端 0.6.3）
- 项目 / 夹具：`project-efb2ed8ad64c839df4b72c3a69772350` / `g4d-draft.md`（`# G4-d 升级安装测试` + 一行正文，28 字符；验证后删除）
- 时间：2026-10-26 01:26–01:31

## 1. 两个构建

| 角色 | 来源 | 说明 |
|---|---|---|
| 旧包 | `git worktree` 在 `17e2bac`（VER-001/002 提交）`assembleDebug` | 与当前 HEAD 不同的代码，同一 `applicationId` |
| 新包 | HEAD `bc47607` 的 `assembleDebug` | 覆盖安装，不 `uninstall`、不 `pm clear` |

覆盖安装命令：`adb install -r app-debug.apk`（两次都成功，未要求卸载）。

## 2. 升级前后链路

1. **旧包登录**：为构造「保存仍在途」的草稿，把服务器地址指向延迟注入代理
   （`tools/g3d_slow_proxy.py --listen 192.168.2.101:18099 --target http://192.168.2.102:18081 --put-delay-ms 4000`），
   在代理上完成一次登录（`login ok`），随后打开 `g4d-draft.md`（`opened … revision=sha256:7b7a7e88…`）。
2. **造草稿**：在文末输入 `DRAFTUP` 后 1.5s 内 `am force-stop`。编辑触发自动保存进入代理延迟窗口
   （代理日志 `delaying PUT …/files/file?path=g4d-draft.md by 4.0s`），进程在保存落定前被杀。
3. **升级前本地状态**：拉取 `databases/novara.db` 后读取，`drafts` 表 2 行，其中
   `project-efb2…\0g4d-draft.md` 的正文以 `…\nDRAFTUP` 结尾、`base_content` 仍是 28 字符原文
   —— 即未提交编辑已落本地草稿。
4. **覆盖安装新包**（`adb install -r`，输出 `Success`），随后冷启动。
5. **升级后**：不出现登录页，日志 `Connection: status ok authenticated=true` —— **持久化 Cookie 跨包有效，免重登**；
   编辑器直接恢复到之前的标签 `g4d-draft.md`（项目 ID / 路径输入框同步为该值），正文含 `DRAFTUP`。
6. **数据库兼容**：再次拉取 `novara.db`，`drafts` 表仍可读，且上轮遗留的 `g3d-a.md` 草稿行仍在（说明 Room schema 跨包未破坏）；
   `g4d-draft.md` 的草稿行已被清除，因为服务器实际收到了那次保存（代理延迟只是延后转发，服务端 `revision` 变为 `30dbb4b6…`，35 字符），
   打开时判定「草稿 == 服务器内容」按设计清理。
7. **功能可用**：进入「写作」页点「挂接」，`状态：空闲`，Agent 侧 active 探测正常（无残留运行）。

## 3. 结论

覆盖安装（不同代码构建、同包名）后：会话免重登、Room 草稿库可读、编辑器标签与项目/文件定位恢复、服务端内容一致、Agent 页可正常挂接。
未覆盖：签名变更（换签名安装会被系统拒绝，本轮两包同 debug 签名）、`versionCode` 降级安装（两包 versionCode 相同，属覆盖替换而非降级）。

## 4. 环境备注

验证期间 `192.168.2.101:18098` 已停止监听（`Invoke-WebRequest` 报「目标计算机积极拒绝」），
因此上一轮记录的 501 `PUT /files/file` 现象在当前环境无法复现；本轮全部操作在 `.102:18081` 完成，
用于注入延迟的代理监听在 `.101:18099`（只做转发，不改变请求/响应）。
