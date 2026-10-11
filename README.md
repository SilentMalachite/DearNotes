# DearNotes

Notion のように1行ずつブロックで書く、手元で動くメモアプリです。Java 25、JavaFX、RichTextFX、SQLite で作っています。macOS と Windows 向けに jpackage でインストーラーを作れます。

## できること

- 行頭に Markdown の記号を打つと、ブロックの種類が変わる（`# ` `## ` `### ` `- ` `1. ` `[] ` `> ` ```` ``` ```` `---`）。日本語入力のまま打った `＃　` なども同じに扱う
- `/` を打つと種類の候補が出る。文字を足すと絞り込める（例: `/見` で見出し）
- Enter で次のブロック、空の行で Backspace を押すと消える。Tab / Shift+Tab で字下げ
- 左端の `⋮⋮` をドラッグするか、Cmd/Ctrl+Shift+↑↓ で並べ替える
- 文字を選んで Cmd/Ctrl+B（太字）、+I（斜体）、+E（コード）、+Shift+S（取り消し線）、+Shift+L（リンク。Cmd/Ctrl を押しながらクリックで開く）
- Markdown を貼り付けると、行ごとにブロックへ分ける（コードの囲みは1ブロックにまとめる）
- ページは入れ子にできる。サイドバーでドラッグして移動し、右クリックで子ページの追加やゴミ箱への移動ができる
- 「今日」でその日の日記ページを開く（Cmd/Ctrl+Shift+D）
- Cmd/Ctrl+K で全ページを検索する。日本語の部分一致は SQLite FTS5 の trigram で探す
- 変更は自動で保存する（入力が 500ms 止まったら、1本の書き込みスレッドでまとめて書く）
- 削除したページはゴミ箱に入り、30日後に消える

## 動かす

JDK 25 が要ります。JavaFX は Gradle が取ってくるので、JavaFX 入りの JDK でなくても構いません。

```sh
./gradlew run                                   # 起動
./gradlew run -Pdearnotes.dataDir=/tmp/dn-test  # 別のデータフォルダで試す
./gradlew test                                  # テスト（UI テストは画面がない環境では飛ぶ）
```

データは次の場所の `dearnotes.db`（SQLite、WAL モード）に保存します。アプリを入れ直しても消えません。

| OS | 場所 |
|---|---|
| macOS | `~/Library/Application Support/DearNotes/` |
| Windows | `%APPDATA%\DearNotes\` |
| その他 | `$XDG_DATA_HOME/DearNotes/`（なければ `~/.local/share/DearNotes/`） |

## インストーラーを作る

```sh
./gradlew jpackage
```

`build/jpackage/` に、macOS では `DearNotes-1.0.1.dmg`、Windows では `DearNotes-1.0.1.msi` ができます。jlink で必要なモジュールだけの JRE を作って同梱するので、利用者の PC に Java は要りません。

- jpackage は他の OS 向けには作れません。各 OS で実行してください
- Windows では WiX Toolset が要ります（`dotnet tool install --global wix`）
- アイコンを付けるときは `packaging/mac/DearNotes.icns` と `packaging/win/DearNotes.ico` を置きます
- 版は `build.gradle.kts` の `version` で決まります。Windows の `--win-upgrade-uuid` は変えないでください（新しい版が古い版を置き換えるのに使います）

GitHub に `v1.0.0` のようなタグを push すると、`.github/workflows/release.yml` が macOS（Apple Silicon）と Windows でインストーラーを作り、Artifacts に置きます。Intel Mac はサポート外です。

## 署名していないアプリを開く

v1 のインストーラーには署名をしていません。初回だけ OS の警告を手で許可してください。

**macOS 15 以降**

1. `.dmg` を開き、DearNotes を「アプリケーション」へドラッグする
2. DearNotes を開くと「開けません」と出るので「完了」を押す
3. 「システム設定 › プライバシーとセキュリティ」を開き、下の方にある「DearNotes は開けませんでした」の横の「このまま開く」を押す
4. もう一度開き、確認で「このまま開く」を押す

macOS 15 からは、Finder で右クリックして「開く」を選ぶ方法は使えません。

**Windows**

`.msi` を開くと「Windows によって PC が保護されました」と出ることがあります。「詳細情報」を押し、「実行」を押してください。ユーザーごとのインストールなので管理者権限は要りません。

## 構成

```
src/main/java/app/dearnotes
├── Launcher, App   起動と終了（終了時に未保存の変更を書いてから DB を閉じる）
├── model           Block, Page, BlockType, Mark, Ids (UUID v7), SortKeys
├── editor          Markdown ショートカット、「/」コマンド、貼り付けの解析、装飾の付け替え
├── db              Database, Migrations, PageRepo, BlockRepo, SearchRepo
├── save            AutoSaver（書き込みスレッド）
└── ui              MainWindow, Sidebar, PageEditor, BlockView, SlashMenu, SearchDialog, TrashDialog
```

設計は `docs/plan/plan.html`、実装計画は `docs/plan/implementation.md` にあります。

## v1 に入れないもの

クラウド同期と複数端末、画像やファイルの添付、共有とコメント、表やデータベース型のページ、自動アップデート、Linux 向けパッケージ。
