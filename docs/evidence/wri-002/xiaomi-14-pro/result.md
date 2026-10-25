# WRI-002 项目文件树：懒加载分页 + 按需展开

- 日期：2026-10-26
- 设备：Xiaomi 14 Pro（2304FPN6DC / Android 16，debug 构建，服务端 `http://192.168.2.102:18081` 0.6.3 @ `85ec97c`）
- 项目 / 目录：`project-efb2ed8ad64c839df4b72c3a69772350`，取证夹具 `pagetest/`（9 个 `page-NN.md`，测完已删除）
- 对应矩阵：`WRI-002`（写作编辑 / 项目文件树；steps「展开整行分页选择与刷新」，expected_ui「树目录状态和选中文件对应」，expected_server_effect「project scope 和路径正确」）
- 实现：
  - `app/src/main/java/com/denova/novara/core/network/ProjectFileTree.kt`（`entries` 续页字段 `cursor`、可选 `entry_budget`、`hasMore`）
  - `app/src/main/java/com/denova/novara/core/project/ProjectFileTree.kt`（`merge` 追加/替换、`pendingMore`、「加载更多」合成行）
  - `app/src/main/java/com/denova/novara/feature/project/ProjectDirectoryViewModel.kt`（续页、按需解析分支、请求世代）
  - `app/src/main/java/com/denova/novara/feature/shell/RightDrawer.kt`（「加载更多」行、debug 分页探针）
- 单测：`app/src/test/java/com/denova/novara/ProjectFileTreeTest.kt`（13 用例，本次新增 7 个）

## 1. 变更要点

| 变更 | 原因 |
|---|---|
| 目录页支持 `continuation` 续页（`POST /files/resolve` 带 `targets[].cursor`） | 此前一次 `recursive=true` 全量铺开，服务端截断后剩下的子项无法取回，用户看到的是不完整目录 |
| 续页按 `revision` 判定追加：revision 未变才 append，变了整页替换 | 对齐 Web `mergeProjectDirectories`；游标只在 revision 未变时有效，盲目追加会得到重复或幽灵条目 |
| 展开未解析的目录时单独 resolve 该分支 | 核对后端 `resolveTreeTarget` 的实现：`follow_single_child_directories` 未开启时递归循环首轮即 break，**`recursive=true` 实际只返回目标目录页**。此前 Android 假定递归能拿到整棵树，导致「点开目录是空的」 |
| 根目录被截断时在树末尾补「加载更多」行 | 根没有对应行，否则根页剩余条目没有任何续页入口 |
| 默认只展开「已经有页」的目录 | 否则未解析的分支显示 `▾` 却无子项，用户第一次点击只会把它折叠掉 |
| debug 构建暴露「调试：强制分页」（`entry_budget=6`） | 服务端默认上限 100000 条，真实项目不可能触达 `children_state=partial`；没有小预算就没有分页验证条件 |

## 2. 真机验证

### 2.1 首页被预算截断（[page-one.png](page-one.png)）

开启探针后：

```text
I ProjectDir: loaded dirs=1 rows=12 partial=0 budget=-1   ← 默认不限制
I ProjectDir: loaded dirs=1 rows=7 partial=1 budget=6     ← 首屏 6 项 + 「加载更多」
```

首屏只返回 `assets / chapters / interactive / log / pagetest / setting` 六项，末尾是「加载更多…」；未解析子目录显示 `▸`（折叠）。

### 2.2 根级续页

```text
I ProjectDir: loaded more path= added=6
```

追加后 `CREATOR.md / g3d-*.md / ideas.md / wri013.md` 等根级文件出现，游标继续有效时「加载更多」保留。

### 2.3 按需展开分支 + 子级续页（[paging.png](paging.png)）

点击 `pagetest`（此时无页）：

```text
I ProjectDir: resolved dir path=pagetest dirs=1 partial=true
```

子项 `page-01.md … page-06.md` 出现，子级末尾再出现「加载更多…」；点击后：

```text
I ProjectDir: loaded more path=pagetest added=3 partial=false
```

`page-07/08/09` 追加，子级「加载更多」消失（`pendingMore` 只剩根）。全程 project scope 未变（`project-efb2`），点击任一文件走既有 `openFile(projectId, path)` 路径。

### 2.4 关闭探针回到默认

```text
I ProjectDir: loaded dirs=1 rows=13 partial=0 budget=-1
```

不再出现「加载更多」，与未设预算的默认行为一致。

夹具清理：`pagetest/` 的 9 个文件与目录经 `/files/operations` 删除，容器内 `ls | grep -c pagetest` = 0。

## 3. 自动化检查

```text
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
BUILD SUCCESSFUL   suites=55 tests=389 failures=0
```

新增用例（`ProjectFileTreeTest`，13 例）：

| 用例 | 覆盖点 |
|---|---|
| `pending more lists only partial pages with a cursor` | `partial` + 游标才算还有下一页，`complete` 带残留游标不算 |
| `merge appends the next page when the revision is unchanged` | 同 revision 追加、状态转 `complete` |
| `merge de-duplicates entries repeated across pages` | 跨页重复路径去重 |
| `merge replaces the page when the revision changed` | revision 变了整页替换（不用旧游标追加） |
| `merge without appendPath keeps other directories untouched` | 非续页请求只更新目标目录 |
| `visible rows insert a load more row after the expanded children` | 子级「加载更多」行位置、深度与标记 |
| `collapsed partial directory shows no load more row` | 折叠时不出「加载更多」 |
| `partial root appends a load more row at tree tail` | 根级截断时补行 |

## 4. 未覆盖 / 已知边界

- 分页边界用 debug 探针（`entry_budget=6`）构造：真实项目目录远小于服务端默认上限，生产路径下不会触发 `partial`；探针按钮只在 `BuildConfig.DEBUG` 显示。
- `cursor_stale`（目录在两次请求之间被改）只在代码里处理为整树重载，未真机构造。
- 文件新建 / 重命名 / 删除 / 移动、git 状态标记、展开状态持久化仍未实现（不属本行 steps）。
- 仪器测试未执行（`connectedDebugAndroidTest` 受 UTP 插件下载 TLS 失败阻塞）。
