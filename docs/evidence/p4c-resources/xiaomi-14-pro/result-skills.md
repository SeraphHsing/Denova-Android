# SKL-001 / SKL-002 真机验证（Skills 作用域、偏好、文档与附属文件）

- **日期：** 2026-10-28（设备本地时间 10-29 凌晨）
- **设备：** Xiaomi 14 Pro（`135babde`，Android 16）
- **服务器：** `http://192.168.2.102:18081`，Denova 0.6.3 @ `85ec97cc047d9e8ea52ebdae0c268c3df1c39a7e`
- **构建：** debug APK，`feat(skills)` + `feat(presets)` + `feat(agents)` 三个提交后的工作树（`assembleDebug`）
- **验证形态：** Android 真机端到端 + 服务端接口读回（`tools/denova_http.py`）。Web 端同场景对照随 P5。
- **凭证：** 仅经环境变量注入，未落盘、未出现在截图或日志。

## 1. SKL-001 作用域与偏好

| 步骤 | 结果 |
|---|---|
| 打开抽屉 → Skills | `Skills: loaded target=global skills=10 scopes=3 shared=false` |
| 点「当前项目」 | `Skills: loaded target=project skills=10 scopes=4 shared=false`；页面「可写位置」由 `user` 变为 `user, workspace`（项目层多了 workspace 目录） |
| 切「在 Denova 中使用通用 Skills」 | `Skills: shared_enabled=true target=project`；`GET /api/projects/:id/skills` 读回 `shared_enabled=true`，同时 `GET /api/skills`（全局）仍为 `false` → **偏好只写目标 scope** |
| 再切回 | `shared_enabled=false`，服务端项目层读回 `false` |
| 单 Skill 开关（用户级 `deai-promax`） | `Skills: preference scope=user name=deai-promax enabled=false`；服务端项目层读回 `enabled=False` |
| 恢复 | `PATCH .../skills/preferences {scope:user,name:deai-promax,enabled:true}` → 读回 `enabled=True, active=True` |

无项目时点击「当前项目」会提示先打开一本书（`skills_project_required`），不发送请求。

## 2. SKL-002 文档与附属文件

| 步骤 | 结果 |
|---|---|
| 打开 `user/deai-promax` | `Skills: opened scope=user name=deai-promax files=7 revision=5b799a1c editable=true`；详情显示路径、`版本 5b799a1c · 7 个文件 · 可编辑`、SKILL.md 正文与目录文件 chips（SKILL.md / banned-words.md / novel-rules.md / patterns.md…） |
| 保存 SKILL.md | 原文备份后替换为带 frontmatter 的探针正文 → `Skills: saved scope=user name=deai-promax revision=f04792ce`；`GET /api/skills/document` 读回 `revision=f04792ce`、`len=70`、内容与探针逐字一致 |
| 恢复原文 | `PUT /api/skills/document`（base_revision=f04792ce）→ 读回 `len=7813`、`equal=True`、`revision=c5bfe3db` |
| 服务端校验（错误原文透传） | 内容不含 frontmatter → `DenovaApiException: file does not start with frontmatter delimiter`；frontmatter 缺 description → `skill description is required`；界面原样展示，不吞错、不重试 |
| 附属文件读取 | 点 `novel-rules.md` chip → `Skills: file opened path=references/novel-rules.md size=1495 revision=114bbe3a`，界面显示文件路径与正文 |
| 附属文件保存 | 正文插入探针 → `Skills: file saved path=references/novel-rules.md revision=fb2922fe`；读回 `len=1512`、`probe=True` |
| 附属文件恢复 | `PUT /api/skills/file`（base_revision=fb2922fe）→ 读回 `len=1495`、`equal=True`、`revision=114bbe3a` |
| 新建 Skill | 对话框填名称 `androidprobe-skill` + 触发说明 → `Skills: created scope=user name=androidprobe-skill path=/data/.denova/skills/androidprobe-skill/SKILL.md`；读回文档 `category=general`、`files=[SKILL.md]`、frontmatter 含 name/description/category |
| 删除 Skill | 二次确认后 → `Skills: deleted scope=user name=androidprobe-skill`；`GET /api/skills` 用户层回到 `['deai-promax']`（10 条），`GET .../document?name=androidprobe-skill` 返回 400 |

名称规则（客户端前置校验）由 `SkillLibraryTest` 覆盖：空 / 非字母数字开头 / 非法字符 / 超 64 字符 / 超 240 字节（含 4 字节补充平面字符）。

## 3. 本轮真机暴露并修复的缺陷

1. **抽屉底部条目可见却点不到**：`AppDrawer` 的 `LazyColumn` 只加了 `statusBars`，最后两条（Skills / Agents）落在手势导航区下方，语义树能查到、点击无响应。改为 `contentPadding` 底部取 `max(navigationBars, systemGestures, 240dp)`，滚到底后条目停在可点区域。
2. **详情 / 表单在列表末尾导致「点了没反应」**：Skills / 创作方案 / Agents 三页的详情或编辑表单挂在列表末尾，用户点「打开 / 新建 / 编辑」后视图不动。三页均增加「选中或开始编辑后自动滚到底」。
3. **创作方案页一直「正在加载…」**：`PresetsViewModel.bindClient` 先把 `loading=true` 再调 `load()`，被 `load()` 的「已在加载即返回」守卫挡住。改为先重置状态再加载。

## 4. 未覆盖 / 边界

- 仪器测试（`connectedDebugAndroidTest`）未执行（环境阻塞：UTP 插件下载 TLS 失败，已登记）。
- 内置 Skill 覆盖（`saveAsOverride` → 写可写作用域）未单独真机构造；同一 `PUT document` 路径的 `target_scope/target_name` 分支仅由服务端契约与代码审阅覆盖。
- Skill 批量导出 / 远程更新（基线 `skills.library.checkAll/updateAll`）**后端无对应端点**（api-inventory 只有 8 条 skills 路由），不在本行验收范围。
