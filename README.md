# DearNotes

[![CI](https://github.com/SilentMalachite/DearNotes/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/SilentMalachite/DearNotes/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/SilentMalachite/DearNotes)](https://github.com/SilentMalachite/DearNotes/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/SilentMalachite/DearNotes/total)](https://github.com/SilentMalachite/DearNotes/releases)
[![License](https://img.shields.io/github/license/SilentMalachite/DearNotes)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Platform](https://img.shields.io/badge/platform-macOS%20%28Apple%20Silicon%29%20%7C%20Windows-lightgrey)](#ダウンロード)

Notion のように1行ずつブロックで書く、手元で動くメモアプリです。Kotlin（JVM 25）、JavaFX、RichTextFX、SQLite で作っています。macOS と Windows 向けに jpackage でインストーラーを作れます。

## ダウンロード

[![macOS (Apple Silicon) 版をダウンロード](https://img.shields.io/badge/Download-macOS%20%28Apple%20Silicon%29-000000?style=for-the-badge)](https://github.com/SilentMalachite/DearNotes/releases/latest)
[![Windows 版をダウンロード](https://img.shields.io/badge/Download-Windows-0078D4?style=for-the-badge)](https://github.com/SilentMalachite/DearNotes/releases/latest)

[最新のリリース](https://github.com/SilentMalachite/DearNotes/releases/latest)から、macOS なら `.dmg`、Windows なら `.msi` を選んでください。Java は同梱しているので要りません。Intel Mac はサポート外です。インストーラーには署名をしていないので、初めて開くときは[署名していないアプリを開く](#署名していないアプリを開く)の手順で許可してください。

## できること

- 行頭に Markdown の記号を打つと、ブロックの種類が変わる（`# ` `## ` `### ` `- ` `1. ` `[] ` `> ` ```` ``` ```` `---`）。日本語入力のまま打った `＃　` なども同じに扱う
- `/` を打つと種類の候補が出る。文字を足すと絞り込める（例: `/見` で見出し）。日本語入力のまま打った `／` や `・` でも出る。空の行には「「/」でコマンド」や「見出し 1」などの案内が薄く出る
- Enter で次のブロック、空の行で Backspace を押すと消える。Tab / Shift+Tab で字下げ
- 左端の `⋮⋮` をドラッグするか、Cmd/Ctrl+Shift+↑↓ で並べ替える
- 文字を選んで Cmd/Ctrl+B（太字）、+I（斜体）、+E（コード）、+Shift+S（取り消し線）、+Shift+L（リンク。Cmd/Ctrl を押しながらクリックで開く）
- Markdown を貼り付けると、行ごとにブロックへ分ける（コードの囲みは1ブロックにまとめる）
- ページは入れ子にできる。サイドバーでドラッグして移動し、右クリックで子ページの追加やゴミ箱への移動ができる
- `[[` を打つと、ほかのページへのリンクの候補が出る（日本語入力のまま打った `「「` でも出る）。文字を足すと名前で絞り込み、候補にない名前なら今のページの子ページを作ってリンクする。Cmd/Ctrl を押しながらクリックでそのページへ移る。ページの下には、そのページへリンクしているページが並ぶ
- 「今日」でその日の日記ページを開く（Cmd/Ctrl+Shift+D）
- Cmd/Ctrl+K で全ページを検索する。日本語の部分一致は SQLite FTS5 の trigram で探す
- 変更は自動で保存する（入力が 500ms 止まったら、1本の書き込みスレッドでまとめて書く）
- 削除したページはゴミ箱に入り、30日後に消える
- ページを Markdown ファイルに書き出す。サイドバーでページを右クリックして「Markdown で書き出す…」を選ぶか、Cmd/Ctrl+Shift+E で開いているページを書き出す。「子ページごと書き出す…」では、選んだフォルダの中に1ページ1ファイルで書く。書き出したページどうしのリンクは `[京都](../京都.md)` のような相対パスのリンクになる

## 動かす

JDK 25 が要ります。ソースは Kotlin です。JavaFX は Gradle が取ってくるので、JavaFX 入りの JDK でなくても構いません。

```sh
./gradlew run                                   # 起動
./gradlew run -Pdearnotes.dataDir=/tmp/dn-test  # 別のデータフォルダで試す
./gradlew check                                 # 全テストと core の検査（UI テストは画面がない環境では飛ぶ）
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

`build/jpackage/` に、macOS では `DearNotes-1.3.3.dmg`、Windows では `DearNotes-1.3.3.msi` ができます。jlink で必要なモジュールだけの JRE を作って同梱するので、利用者の PC に Java は要りません。

- jpackage は他の OS 向けには作れません。各 OS で実行してください
- Windows では WiX Toolset が要ります（`dotnet tool install --global wix`）
- アイコンは `packaging/mac/DearNotes.icns`（macOS）と `packaging/win/DearNotes.ico`（Windows）を使います。ウィンドウのアイコンは `src/main/resources/app/dearnotes/icon-*.png` です。絵を変えるときは `packaging/icon/build-icons.py` を直し、macOS で `python3 packaging/icon/build-icons.py` を実行して全部を作り直します（`brew install librsvg imagemagick` が要ります）
- 版は `build.gradle.kts` の `version` で決まります。Windows の `--win-upgrade-uuid` は変えないでください（新しい版が古い版を置き換えるのに使います）

### リリースする

GitHub に `v1.0.0` のようなタグを push すると、`.github/workflows/release.yml` が macOS（Apple Silicon）と Windows でインストーラーを作り、GitHub Release に付けて公開します。Release の本文は、CHANGELOG.md のその版の節から作ります。Intel Mac はサポート外です。

1. `build.gradle.kts` の `version` を新しい版にする
2. CHANGELOG.md の `[Unreleased]` の中身を、新しい版の節（`## [1.2.3] - 日付`）に移し、末尾の比較リンクも足す
3. この2つを main にマージしてから、タグを付けて push する

```sh
git tag -a v1.2.3 -m "DearNotes 1.2.3"
git push origin v1.2.3
```

タグと `version` が違うときや、CHANGELOG.md にその版の節がないときは、Release を作らずに失敗します。Actions の画面から手で動かしたときは、インストーラーを Artifacts に置くだけです。

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

画面に依存しないロジックは `core/`（Kotlin Multiplatform。今のターゲットは JVM だけ）に、アプリ本体はルートにあります。`core/src/commonMain` と `core/src/commonTest` では JDK の API を使わず、`./gradlew check` で確かめます。

```
core/src/commonMain/kotlin/app/dearnotes
├── model           Block, Page, BlockType, Mark, Ids (UUID v7), SortKeys
├── editor          Markdown ショートカット、「/」コマンド、ページ間リンク、折りたたみと目次、貼り付けの解析、装飾の付け替え
└── export          Markdown の書き出し（MarkdownWriter, Inline, Links）

src/main/kotlin/app/dearnotes
├── Launcher, App   起動と終了（終了時に未保存の変更を書いてから DB を閉じる）
├── db              Database, Migrations, DataDir, PageRepo, BlockRepo, SearchRepo
├── save            AutoSaver（書き込みスレッド）, BlockStore, SaveStatus
├── export          ExportTree（ファイルへの書き出し）
└── ui              MainWindow, Sidebar, PageEditor, BlockView, ImeArea / ImeGuard（日本語入力）, SlashMenu, PageLinkMenu, Backlinks, OutlinePanel（目次）, SearchDialog, TrashDialog
```

設計は `docs/plan/plan.html`、実装計画は `docs/plan/implementation.md` にあります。画面を JavaFX から Compose Multiplatform に移す計画は `docs/plan/kmp-migration-design.md`（設計）と `docs/plan/kmp-migration-plan-stage0-1.md`（段階 0〜1 の計画）に、日本語入力の点検表は `docs/plan/ime-checklist.md` にあります。

## v1 に入れないもの

クラウド同期と複数端末、画像やファイルの添付、共有とコメント、表やデータベース型のページ、自動アップデート、Linux 向けパッケージ。

## ドキュメント

- [CHANGELOG.md](CHANGELOG.md): 版ごとの変更
- [CONTRIBUTING.md](CONTRIBUTING.md): 不具合の報告、開発の準備、変更の送り方
- [SECURITY.md](SECURITY.md): 脆弱性の報告方法
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md): インストーラーに入っているソフトウェアのライセンス

## ライセンス

[Apache License 2.0](LICENSE) です。インストーラーに同梱している JRE、JavaFX、RichTextFX などは、それぞれのライセンスに従います（[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）。
