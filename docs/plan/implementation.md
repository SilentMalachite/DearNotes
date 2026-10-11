# DearNotes 実装計画

設計は `docs/plan/plan.html` に従う。決定事項は次の4つ。

| 論点 | 決定 |
|---|---|
| エディタ | JavaFX + RichTextFX |
| 署名 | v1 では署名しない（README に許可手順を書く） |
| ビルド | Gradle (Kotlin DSL) |
| 本文の保存 | 1ブロック = 1行（`blocks` テーブル） |

## 技術スタック

| 項目 | 版 |
|---|---|
| Java | 25（Gradle toolchain） |
| JavaFX | 25.0.4（Maven の OS 別 jar） |
| RichTextFX | 0.11.7 |
| sqlite-jdbc | 3.53.4.0（FTS5 trigram 対応） |
| JUnit | 6.1.3 |

### plan.html からの変更点

plan.html は「JavaFX 入り JDK (Liberica Full) の jmods を jlink する」としていた。実装では JavaFX を Maven の jar としてクラスパスに載せ、jlink は JDK のモジュールだけで実行時イメージを作る。

- 理由: 手元と CI の JDK を選ばない（Temurin 25 でもビルドできる）。JavaFX の版を Gradle で固定できる。
- 結果: `app.dearnotes.Launcher`（`Application` を継承しない main）から起動する。plan.html にもこのクラス名がある。
- CI は `actions/setup-java` の `temurin` 25 を使う。

## パッケージ構成

```
app.dearnotes
├── Launcher, App                      起動、終了時の flush
├── model   BlockType, Mark, Block, Page, Ids(UUID v7), SortKeys
├── editor  MarkdownShortcuts, MarksCodec(JSON), SlashCommands
├── db      DataDir, Database, Migrations, PageRepo, BlockRepo, SearchRepo
├── save    AutoSaver, SaveStatus
└── ui      MainWindow, Sidebar, PageEditor, BlockView, SlashMenu,
            SearchDialog, SaveIndicator, dearnotes.css
```

## フェーズ

各フェーズでテストを先に書き、`./gradlew test` が通ってから次へ進む。

### 1. ビルドの土台
- `settings.gradle.kts`, `build.gradle.kts`, Gradle wrapper
- 依存: JavaFX（OS とアーキテクチャから classifier を決める）, RichTextFX, sqlite-jdbc, JUnit
- タスク: `run`, `test`, `copyLibs`, `jlinkRuntime`, `jpackage`

### 2. モデル（純粋な Java、テスト中心）
- `BlockType`（10種）, `Mark`, `Block`（record, `with*` メソッド）, `Page`
- `Ids.uuidV7()` — 時刻順の UUID
- `SortKeys.between(prev, next)` — 間が狭いと `null`、呼び出し側が 1024 間隔で振り直す
- `MarkdownShortcuts.match(prefix)` — `# ` `## ` `### ` `- ` `* ` `1. ` `[] ` `> ` ```` ``` ```` `---`
- `MarksCodec` — `[[0,5,"bold"],…]` の読み書き（外部ライブラリなし）
- テスト: SortKeysTest, MarkdownShortcutsTest, MarksCodecTest, IdsTest

### 3. データベース
- `DataDir.dataDir()` — mac: `~/Library/Application Support/DearNotes`, win: `%APPDATA%\DearNotes`, 他: `~/.local/share/DearNotes`
- `Database.open(path)` — WAL, synchronous=NORMAL, foreign_keys=ON, busy_timeout=5000。`close()` で `wal_checkpoint(TRUNCATE)`
- `Migrations` — `PRAGMA user_version` で版管理。v1 で `pages`, `blocks`, `blocks_fts`(trigram) と3つのトリガーを作る
- `PageRepo` — create, rename, setIcon, listAlive, listTrash, move, openDaily（`ON CONFLICT(daily_date) DO NOTHING`）, trash（再帰 CTE で子も）, restore, purgeOlderThan(30日), deleteForever
- `BlockRepo` — listByPage, upsertAll（1トランザクション）, deleteAll(ids), renumber(pageId)
- `SearchRepo.search(q)` — 3文字以上は FTS5 MATCH（引用）、2文字以下は LIKE。タイトルの LIKE と UNION、ゴミ箱を除き 20件
- テスト: 一時ファイルの DB で各 Repo、FTS の日本語部分一致、ゴミ箱の CASCADE、日記の二重作成防止

### 4. 自動保存
- `AutoSaver` — FX スレッドから `changed(block)` / `deleted(id)`。500ms 入力が止まったら `db-writer` スレッド1本で `flushNow()`
- 状態: SAVED → DIRTY → SAVING → SAVED / FAILED（5秒後に再試行、新しい入力は上書きしない）
- `close()` で残りを書いてから終わる
- テスト: 状態遷移、失敗時の再試行、close での flush（UI スレッドはコールバックを差し替える）

### 5. UI
- `MainWindow` — 左に `Sidebar`、右に `PageEditor`、上にタイトルと `SaveIndicator`
- `Sidebar` — 「今日」ボタン、ページの `TreeView`（parent_id から木を作る）、デイリー一覧、ゴミ箱。右クリックで新規子ページ・名前変更・ゴミ箱へ。ドラッグで移動
- `PageEditor` — `VBox` にブロックを縦に並べる。convert / splitAt / mergeUp / indent / moveBlock
- `BlockView` — ブロック1つ分の RichTextFX エリア（高さは内容に合わせる）。種類ごとの見た目（見出し、箇条書きの点、番号、チェックボックス、引用線、コード、区切り線）
  - 行頭で Markdown ショートカットを打つと種類が変わる
  - Enter で分割、空の行で Backspace を押すと消えて前の行に結合、Tab / Shift+Tab で字下げ
  - ↑↓ で前後のブロックへ移動
  - Cmd/Ctrl+B / I / Shift+S / E で太字・斜体・取り消し線・コード
  - 左端のハンドルをドラッグして並べ替え（sort_key は1行だけ変わる）
- `SlashMenu` — `/` で候補を出し、文字を足すと絞る。Enter で種類を変える
- `SearchDialog` — Cmd/Ctrl+K。結果を ↑↓ で選び、Enter で開く
- 起動時に 30日を過ぎたゴミ箱を消す

### 6. 配布
- `jlinkRuntime` — `java.base,java.sql,java.desktop,java.logging,java.xml,java.naming,jdk.unsupported,jdk.charsets,jdk.localedata,jdk.crypto.ec` を `--strip-debug --no-header-files --no-man-pages` で
- `jpackage` — mac は `.dmg`、Windows は `.msi`（`--win-menu --win-shortcut --win-per-user-install --win-upgrade-uuid`）。アイコンは `packaging/` に置けば使う
- `.github/workflows/release.yml` — タグ `v*` で macos-latest / macos-15-intel / windows-latest がそれぞれ作る
- `README.md` — ビルド方法、未署名アプリを開く手順（macOS 15 以降、Windows SmartScreen）

### 7. 確認
- `./gradlew test` が全部通る
- `./gradlew run` で起動し、ページ作成・入力・ショートカット・検索・再起動後の保持を確かめる
- `./gradlew jpackage` で mac の `.dmg` ができる

## 実装中に足したもの

- 日本語入力のまま打った `＃　` `ー　` `「」　` なども行頭ショートカットとして扱う（`MarkdownShortcuts.halfWidth`）
- 複数行の貼り付けを `editor/PasteParser` で Markdown として解析し、ブロックに分ける。```` ``` ```` の囲みは1つのコードブロックにする
- `PageEditorUiTest`: 本物の画面にキーイベントを直接送るテスト。OS の IME を通さないので結果が安定する。画面がない環境では飛ぶ
- `-Pdearnotes.dataDir=…` で別のデータフォルダを使って起動できる

## v1 に入れないもの
同期、画像と添付、共有、表・カンバン・データベース、自動アップデート、Linux 向けパッケージ。
