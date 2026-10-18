# Compose Multiplatform への移行 設計

## なぜ移すか

日本語入力の不具合をなくしたい。JavaFX + RichTextFX では、次の2つが起きる。

- スラッシュコマンドが正しく動かないことがある
- 変換を確定すると改行されてしまう（確定の Enter が、ブロックの改行にも届く）

`ui/ImeArea` と `ui/ImeGuard` で補ってきた。v1.3.2 で「確定で行が分かれる」「先頭の文字が欠ける」を直したことになっているが、持ち主によると今も頻繁に起き、実質まったく直っていない。根本は JavaFX と RichTextFX の入力処理にあり、直しきれない。UI を Compose Multiplatform（デスクトップ）に書き直して、入力の土台ごと替える。

## 目標

- 今の機能がすべて Compose 版で動く（README の「できること」の全項目）
- 上の日本語入力の2つの問題が、macOS（Apple Silicon）と Windows の両方で起きない
- JavaFX を取り除き、v2.0.0 として .dmg と .msi で配る

## 目標にしないこと

- iOS / Android 対応。いまは考えない。ただし純粋なロジックは KMP の `commonMain` に置き、将来足せる形にはしておく
- 見た目や操作の作り直し。今とほぼ同じにする
- JavaFX 版（1.x）の保守。移行中も修正やリリースはしない。移行が終われば捨てる
- Linux や Intel Mac の配布（今もサポート外）

## 失敗とみなすこと（動いても駄目なもの）

- 既存の `dearnotes.db` が開けない、または中身が変わる・失われる
- 1.x を入れた人が、v2.0 を入れると別アプリとして並んでしまう（アップグレードにならない）
- 日本語入力の不具合が、形を変えて残る（例: 確定で改行はしないが、文字が欠ける）

## 守ること（制約）

- **データ**: DB のファイル、場所、スキーマ、`Migrations` はそのまま。データフォルダの決め方（`DataDir`、`-Pdearnotes.dataDir`）も変えない
- **アップグレード**: Windows の upgrade UUID（`5f0c7c2e-3b7a-4d4e-9a63-2a1d8e6b9c41`）と per-user インストール、macOS の bundle ID（`app.dearnotes`）、アプリ名とベンダー名（`DearNotes`）を引き継ぐ
- **設定**: 1.x が `java.util.prefs` に残している設定（最後に開いたページ、書き出し先のフォルダ）を引き継ぐ（Claude の判断）
- **JDK**: 今と同じ JDK 25 のツールチェーン。Kotlin 2.4 系。同梱する実行環境はまず今の Temurin で試し、段階 0 で Temurin では日本語入力が通らず JetBrains Runtime（JBR 25）なら通る場合は、JBR に替えてよい（Claude の判断）。替えたときは CI、release.yml、THIRD_PARTY_NOTICES も合わせて直す
- **ライセンス表**: `THIRD_PARTY_NOTICES.md` を、同梱する依存に合わせて直す（OpenJFX と RichTextFX 系を消し、Compose / Skiko などを足す）

## 決まっていること

| 論点 | 決定 |
|---|---|
| UI | Compose Multiplatform（デスクトップ） |
| DB | 今の sqlite-jdbc と自前のリポジトリ層（`db/*`）をそのまま使う。JVM 専用でよい |
| 純粋なロジック | `model` / `editor` / `export`（ファイルを書く `ExportTree` は除く）を KMP モジュールの `commonMain` に移す。ターゲットは当面 JVM だけ |
| 配布 | Compose のパッケージ機能（nativeDistributions）で .dmg / .msi を作る |
| JavaFX 版 | 移行が終わったら消す。並行して保守しない |
| 進め方 | 本格的に書き直す前に試作で確かめる。駄目なら止まって、JavaFX のまま直す道を選び直す |
| 日本語入力が標準の入力欄で通らないとき | Compose の低レベルな仕組み（テキスト入力のセッションや、下にある AWT の入力メソッドのイベント）を使い、日本語入力の処理を自前で書いてよい。Swing の部品の埋め込み（SwingPanel など）は使わない |
| 確かめる人 | macOS は Claude が手元で試せる範囲で試し、最終確認は持ち主。Windows は持ち主の実機で、持ち主が打って確かめる。Claude は手順と記録表を渡し、結果を待つ |
| CI | 自動テストは CI で回し続ける |

## 進め方

### 段階 0: 試作（使い捨て）で確かめる

Compose のテキスト入力が、DearNotes の書き方に耐えるかを先に見る。本体とは別の小さなアプリで作り、残さない。

確かめる環境（Claude の判断）: 合格の条件にするのは、macOS は標準の日本語入力でライブ変換オン（既定）・ローマ字入力と、Google 日本語入力の既定の設定（持ち主がふだん使っている。2026-10-18 に持ち主の判断で追加）、Windows は 11 の新しい Microsoft IME。macOS のライブ変換オフ、かな入力、Windows 10、以前のバージョンの Microsoft IME は、確かめて結果を記録するが、合否には入れない。

確かめること（上の環境の macOS と Windows の両方で）:

1. 変換中の文字が、入力欄の中に下線つきで出る。候補窓がキャレットの近くに出る（空の行でも）
2. 変換を Enter で確定しても、新しいブロックができない。確定のあとにもう一度 Enter を押すと、新しいブロックができる
3. `/` と、日本語入力のまま打った `／` や `・`（Google 日本語入力の既定）でスラッシュメニューが出て、続けて打った日本語（例: `/見`）で絞り込める
4. 日本語入力のまま打った `「「` でページリンクの候補が出る。`＃　` で見出しに変わる
5. 確定した文字の先頭が欠けない。確定で行が分かれない
6. 太字・リンクなどの装飾が、入力欄の中で表示されたまま編集できる（変換中も崩れない）
7. ブロックごとに入力欄を分けたとき、↑↓ での行の移動、空の行での Backspace による削除、Enter での分割が自然に動く

試す順番:

1. まず Compose 標準の入力欄（`BasicTextField`）で 1〜7 を試す
2. 1〜5 のどれかが通らなければ、日本語入力の処理を Compose の低レベルな仕組みで自前で書いて試す。この場合、段階 3 のエディタはこの自前の入力欄の上に作ることになり、手間が大きく増える。どの程度増えるかも試作の結果に書く

今の版では、確定時の改行とスラッシュコマンドの不具合が頻繁に起きる。どの OS でどんな手順のときに起きるかはまだ分かっていないので、試作の前に今の版（1.3.2 以降）で両 OS の失敗の手順を記録し、試作でも同じ手順で確かめる。

Windows の列は、Claude が渡す手順と記録表に沿って持ち主が実機で埋める。結果を表にして、持ち主が「進む / やめる」を決める。自前の入力処理でも 1〜5 のどれかがどうしても満たせないなら、Compose への移行はやめる。6・7 は回避策があれば進んでよい。試作の期間の上限は決めていない。

### 段階 1: 純粋なロジックを KMP モジュールに分ける

JavaFX 版を動かしたまま、ロジックだけを先に移す。アプリの挙動は変えない。

- Gradle をマルチモジュールにする。`:core`（KMP、JVM ターゲット）と、アプリ本体
- `commonMain` に `model` / `editor` / `export`（`ExportTree` 以外）を移す。JVM 専用の API を置き換える
  - `Ids`: `SecureRandom` / `UUID` / `System.currentTimeMillis` / `@Synchronized` → Kotlin 標準の乱数・UUID・時刻と、共通で使える排他
  - `Page.dailyDate`: `java.time.LocalDate` → kotlinx-datetime の `LocalDate`（DB との変換は JVM 側で）
  - `Inline` / `PasteParser` / `SlashCommands`: `java.util.regex.Pattern` → `Regex`、`EnumMap` などの JDK コレクション → 標準のもの、`Character.getType` → `Char.category`、`Locale.ROOT` → 不要
  - `Strings.kt` の `isJavaWhitespace` 系はすでに共通で使える形なので、そのまま移す
- `db` / `save` / `ExportTree` は JVM 側に置く。置き場所（`:core` の `jvmMain` か、別の JVM モジュールか）は作る人が決める
- テストも分ける。共通部分は kotlin.test、JVM 部分は今の JUnit のまま
- この段階が終わった時点で、JavaFX 版がこれまでどおり動き、全テストが通る

### 段階 2〜4: Compose で UI を書き直す

画面ごとに、今の `ui/*` と同じ振る舞いを Compose で作る。DB、自動保存、書き出しは段階 1 のものを使う。

- 段階 2: 窓、サイドバー（入れ子・ドラッグ移動・右クリックメニュー）、ページを開く、DB と `AutoSaver` のつなぎ込み（UI スレッドへの戻し方を Compose に合わせる）、保存中の表示、初回の案内（`Welcome`）、エラー表示
- 段階 3: エディタ。ブロックの一覧、Markdown の記号での種類変更、スラッシュメニュー、ページリンクの候補、装飾のショートカット、Markdown の貼り付け、並べ替え（ドラッグと Cmd/Ctrl+Shift+↑↓）、字下げ、折りたたみと目次、バックリンク
- 段階 4: 検索（Cmd/Ctrl+K）、ゴミ箱、今日の日記（Cmd/Ctrl+Shift+D）、Markdown 書き出し（1ページ、子ページごと）

キー操作は README の「できること」と今の実装に合わせる。迷ったら今の JavaFX 版の動きが正しい。

### 段階 5: テスト

- 純粋なロジックと DB のテストは段階 1 のまま使う
- UI のテスト（今は `PageEditorUiTest` を中心に 56 件）は、操作の筋書きを残して Compose の UI テストで書き直す
- CI は段階 1 から毎回の PR で自動テストを回す。今の ubuntu（xvfb）に加えて、macOS と Windows のランナーでもビルドとテストを回す（Claude の判断）。`ci.yml` は段階 1 と段階 2 で新しいビルドに合わせて直す
- 日本語入力の処理を自前で書いた場合は、変換中・確定・取り消しのイベントの並びを入力欄に直接流し込むテストを書き、CI で回す。本物の日本語入力の代わりにはならないが、筋書きの退行は防げる（Claude の判断）
- 日本語入力は自動テストで本物の入力を再現しきれない。段階 0 の 1〜7 を、macOS と Windows で手で確かめる点検表にして `docs/` に残し、リリースの前に毎回通す

### 段階 6: 配布と後片付け

- nativeDistributions で .dmg / .msi を作る。上の「アップグレード」の値を引き継ぐ。版は 2.0.0
- 1.x を入れた環境に v2.0 を入れて、アップグレードになり、データがそのまま開けることを macOS と Windows で確かめる
- リリース（`release.yml`）を新しいビルドに合わせる
- JavaFX、RichTextFX、`ImeArea` / `ImeGuard` など JavaFX 専用のコードと、jlink / jpackage の自前タスクを消す
- README、CONTRIBUTING、THIRD_PARTY_NOTICES を直す

## 作る人に任せること

- 使うライブラリの版（Compose Multiplatform、kotlinx-datetime など）。その時点の安定版でよい
- ブランチの切り方と PR の分け方。段階ごとに PR を分けるのが望ましい
- モジュールの名前と、JVM 専用コードの置き場所
- 入力欄の作り（ブロックごとに入力欄を分けるか、など）。段階 0 の結果で決める
- 状態管理の作り（ViewModel 風にするか、など）
- UI テストの書き方の細部
