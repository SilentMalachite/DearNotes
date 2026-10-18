# Compose 移行 段階 0〜1 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 日本語入力が Compose で通るかを試作で確かめ（段階 0）、並行して純粋なロジックを KMP モジュール `:core` に移す（段階 1）。

**Architecture:** 段階 0 は本体と切り離した使い捨ての Gradle ビルド（`spike/compose-ime/`、ブランチ `spike/compose-ime`、main には入れない）。段階 1 はルートのアプリ（JavaFX のまま）に KMP のサブプロジェクト `:core`（ターゲットは JVM だけ）を足し、`model` / `editor` / `export`（`ExportTree` 以外）をパッケージ名を変えずに移す。DB、自動保存、`ExportTree`、UI はルートに残す。

**Tech Stack:** Kotlin 2.4.21、Kotlin Multiplatform、Compose Multiplatform（デスクトップ、試作のみ）、kotlinx-datetime、kotlin.test、JUnit 6、JDK 25（Temurin）

**Spec:** `docs/plan/kmp-migration-design.md`

**段階 2〜6 について:** 入力欄の作り（標準の `BasicTextField` か、自前の日本語入力処理か）と同梱する実行環境（Temurin か JBR か）は、段階 0 の結果で決まる。段階 2〜6 の計画は Task 5 のあとに別に書く。段階 1 は結果に関係なく役に立つので、段階 0 で持ち主の点検を待つ間に進めてよい。

## Global Constraints

- DB のファイル、場所、スキーマ、`Migrations` は変えない。`DataDir` と `-Pdearnotes.dataDir` も変えない
- JDK 25 のツールチェーン、Kotlin 2.4 系
- 段階 1 の間、JavaFX 版はこれまでどおり動き、全テストが通る。アプリの挙動は変えない
- パッケージ名（`app.dearnotes.model` など）は変えない
- 同梱する依存を足したら `THIRD_PARTY_NOTICES.md` を直す
- ライブラリの版は、その時点の安定版
- 日本語入力の合格条件にする環境: macOS は標準の日本語入力でライブ変換オン・ローマ字入力と、Google 日本語入力の既定の設定（2026-10-18 に追加）、Windows は 11 の新しい Microsoft IME
- Swing の部品の埋め込み（SwingPanel など）は使わない
- Windows の点検は持ち主が実機で行う。Claude は手順と記録表を渡し、結果を待つ
- コミットメッセージは `<type>: <説明>`（feat / fix / refactor / docs / test / chore / perf / ci）。説明は日本語

## Review Focus

1. 1.x が書いた `daily_date`（`'2026-10-11'` のような文字列）を、kotlinx-datetime に替えたあとも同じ日付として読めること（Task 8 にテストを足す）
2. 同じミリ秒の中で 4096 個を超える ID を作っても、ID が重複せず作った順に並ぶこと（Task 8）
3. 複数のスレッドから同時に ID を作っても重複しないこと（Task 8）
4. 絵文字など BMP の外の記号や句読点が、太字の境目に接していても、Markdown の書き出しが変わらないこと（Task 10。変更前の出力を期待値として固定する）
5. インストーラーに `:core` の jar が入り、インストールしたアプリが起動してページを開けること（Task 11）

---

## 段階 0: 試作で確かめる

### Task 1: 日本語入力の点検表を作り、今の版の失敗を記録する

**Files:**
- Create: `docs/plan/ime-checklist.md`

**Interfaces:**
- Produces: 点検表 `docs/plan/ime-checklist.md`。Task 3 と Task 4、段階 5 のリリース前点検で使う

- [ ] **Step 1: 点検表を書く**

列は「項目 / 手順 / 期待 / macOS の結果 / Windows の結果 / メモ」。行は設計書の段階 0 の 1〜7 に、次の 2 行を足す:

- A. 今の版で確定時に改行される手順（持ち主が見つけた手順をそのまま書く）
- B. 今の版でスラッシュコマンドがおかしくなる手順（同上）

各行の手順は、打つキーを 1 つずつ書く（例: `/` → `m` → `i` → スペース（変換）→ Enter（確定））。結果の欄は「○ / × / 一部」と、× のときに何が起きたか。表の先頭に、記録する環境（OS の版、日本語入力の種類と設定、アプリの版、実行環境）を書く欄を置く。合格条件の環境以外（macOS のライブ変換オフ・かな入力、Windows 10、以前のバージョンの Microsoft IME）の結果を書く欄も、合否とは別に置く。

- [ ] **Step 2: 持ち主に、今の版（main の `./gradlew run`）で点検表を埋めてもらう**

持ち主に macOS と Windows の両方で、A・B と 1〜7 を記録してもらう。A・B の手順が分からない場合は、1〜7 を打つ中で × になった手順を A・B に写す。結果が戻るまで Task 2 と段階 1 は進めてよい。

- [ ] **Step 3: Commit**

```bash
git add docs/plan/ime-checklist.md
git commit -m "docs: 日本語入力の点検表を足す"
```

### Task 2: Compose 標準の入力欄で試作を作る

使い捨て。テストは書かない。main には入れない。

**Files:**
- Create（ブランチ `spike/compose-ime` だけ）: `spike/compose-ime/settings.gradle.kts`, `spike/compose-ime/build.gradle.kts`, `spike/compose-ime/src/main/kotlin/Main.kt`

**Interfaces:**
- Consumes: なし（本体のコードは参照せず、必要なロジックは写す）
- Produces: `./gradlew run`（`spike/compose-ime/` で）で開く試作アプリと、イベントの記録ファイル

- [ ] **Step 1: ブランチを切り、独立した Gradle ビルドを作る**

`git switch -c spike/compose-ime main`。`spike/compose-ime/` は自前の `settings.gradle.kts` を持ち、ルートのビルドに含めない。Compose Multiplatform のデスクトップ（`compose.desktop.currentOs`）、Kotlin 2.4.21、JDK 25。

- [ ] **Step 2: 試作の画面を作る**

1 つの窓に、ブロックの縦の並び。ブロックごとに `BasicTextField`（`TextFieldValue` 版。`composition` で変換中かを見られる）。次を入れる:

- Enter: 変換中（`composition != null`）なら何もしない。そうでなければカーソル位置でブロックを分け、次のブロックへ移る
- 空のブロックで Backspace: ブロックを消し、前のブロックの末尾へ移る。↑↓: 前後のブロックへ移る
- ブロックの文字が `/`・`／`・`・` のどれかで始まり、改行と空白を含まないとき、ブロックの下に候補（「見出し1」「見出し2」「箇条書き」など数個）を出し、`/` の後ろの文字で絞り込む
- `「「` か `[[` を打ったら「ページリンクの候補」と出す。行頭の `＃　` か `# ` で見出しの見た目に変える
- 1 つ目のブロックの 0〜2 文字目に太字を付けて表示する（`visualTransformation` か、その時点の API で装飾付きの表示ができる方法）

- [ ] **Step 3: イベントを記録する**

キーの押下（`onPreviewKeyEvent`。キー、種類、`awtEventOrNull` の中身）と、`TextFieldValue` の変化（文字、選択、`composition`）を、時刻つきで `~/compose-ime-log.txt` に追記する。Windows の結果を持ち主から受け取るときに、このファイルも送ってもらう。

- [ ] **Step 4: 起動して、英字の入力で Enter・Backspace・↑↓・スラッシュの候補が動くことを確かめる**

Run: `cd spike/compose-ime && ./gradlew run`
Expected: 窓が開き、英字で打った `/mi` で候補が絞られ、Enter でブロックが分かれる

- [ ] **Step 5: Commit（試作のブランチに）**

```bash
git add spike/compose-ime
git commit -m "chore: Compose の日本語入力の試作を作る"
git push -u origin spike/compose-ime
```

### Task 3: 試作を両 OS で点検する

**Files:**
- Modify: `docs/plan/ime-checklist.md`（main。結果の欄を足す）

- [ ] **Step 1: 持ち主に、試作（Temurin）で点検表の 1〜7 と A・B を macOS と Windows で埋めてもらう**

持ち主に渡すもの: 試作のブランチの取り出し方、起動の手順（`cd spike/compose-ime && ./gradlew run`）、点検表、記録ファイルの場所。

- [ ] **Step 2: 1〜5 か A・B に × があれば、JBR 25 で同じ点検をしてもらう**

試作の `build.gradle.kts` で JDK のツールチェーンを JetBrains Runtime 25 に替える（`vendor = JvmVendorSpec.JETBRAINS`）。結果は Temurin の列と分けて記録する。

- [ ] **Step 3: 記録ファイルを読み、× の原因を書く**

× の行ごとに、記録ファイルのどのイベントの並びで崩れたか（例: 確定のあとに Enter の押下が届き、`composition` はすでに null）をメモ欄に書く。

- [ ] **Step 4: Commit**

```bash
git add docs/plan/ime-checklist.md
git commit -m "docs: 標準の入力欄で試した日本語入力の結果を記録する"
```

### Task 4（Task 3 で 1〜5 か A・B に × が残ったときだけ）: 自前の日本語入力処理で試作する

**Files:**
- Create（ブランチ `spike/compose-ime` だけ）: `spike/compose-ime/src/main/kotlin/ImeField.kt`
- Modify: `docs/plan/ime-checklist.md`（main）

**Interfaces:**
- Produces: `ImeField`（自前の入力欄）。段階 3 のエディタの土台にするかどうかを Task 5 で決める

- [ ] **Step 1: 自前の入力欄を作る**

Compose の低レベルなテキスト入力の仕組み（その時点の `PlatformTextInputModifierNode` などのテキスト入力セッション）か、下にある AWT の `InputMethodEvent`（変換中の文字、確定した文字の数、キャレット）を直接受けて、変換中の文字・確定・取り消しを自分で持つ。候補窓の位置は、キャレットの画面上の位置を返す（空の行でも）。確定の直後に届く Enter は、変換の確定として捨てる（今の `ImeGuard` の考え方。ただし Compose のイベントの順で確かめ直す）。表示は `BasicText` などで自分で描く。Task 2 の Enter・Backspace・↑↓・スラッシュ・`「「`・`＃　`・太字を、この入力欄で作り直す。

- [ ] **Step 2: Task 3 と同じ点検を持ち主にしてもらい、結果を別の列に記録する**

- [ ] **Step 3: 段階 3 の手間がどれだけ増えるかを見積もり、点検表の末尾に書く**

自前で持つ必要があるもの（選択、コピー・貼り付け、取り消し、マウスでのカーソル移動、スクロールなど）を列挙する。

- [ ] **Step 4: Commit**

```bash
git add docs/plan/ime-checklist.md
git commit -m "docs: 自前の日本語入力処理で試した結果を記録する"
```

### Task 5: 結果をまとめ、持ち主に「進む / やめる」を決めてもらう

**Files:**
- Create: `docs/plan/kmp-spike-results.md`

- [ ] **Step 1: 結果を書く**

項目は、点検の結果の要約（1〜7、A・B を OS ごと、Temurin / JBR ごと、標準 / 自前ごと）、選ぶ入力欄、選ぶ実行環境、段階 3 の手間の見積もり、残る不安。

- [ ] **Step 2: 持ち主に決めてもらい、その答えを書く**

設計書の基準は、自前の入力処理でも 1〜5 のどれかが満たせなければ移行をやめる、6・7 は回避策があれば進む、というもの。持ち主の答え（進む / やめる）と日付を書く。

- [ ] **Step 3: Commit**

```bash
git add docs/plan/kmp-spike-results.md
git commit -m "docs: Compose の試作の結果と判断を記録する"
```

- [ ] **Step 4: 進むなら段階 2〜6 の計画を書く。やめるなら、JavaFX のまま直す計画を立て直す（どちらも新しい計画として別に書く）**

---

## 段階 1: 純粋なロジックを `:core` に移す

ブランチ: `refactor/core-module`（main から）。Task 6〜11 をこのブランチで進め、最後に 1 本の PR にする。

### Task 6: CI を macOS と Windows でも回す

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: ジョブを OS のマトリクスにする**

`strategy.matrix.os: [ubuntu-latest, macos-latest, windows-latest]`、`fail-fast: false`。手順は今と同じ（Temurin 25、setup-gradle）。テストの実行は、ubuntu は `xvfb-run --auto-servernum ./gradlew test`、ほかは `./gradlew test`。全手順で `shell: bash`。

- [ ] **Step 2: push して、3 つの OS で CI が通ることを確かめる**

Run: `git push -u origin refactor/core-module` のあと `gh run watch`
Expected: 3 つのジョブがすべて成功。macOS / Windows のランナーで UI テストが不安定なら、そのランナーだけ UI テストを飛ばす設定にし、理由を `ci.yml` のコメントに書く

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: macOS と Windows でもテストを回す"
```

### Task 7: `:core` モジュールを作り、モデルの型と文字列の補助を移す

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Create: `core/build.gradle.kts`
- Move（`git mv`）: `src/main/kotlin/app/dearnotes/model/{Block,BlockType,Mark,SortKeys}.kt` → `core/src/commonMain/kotlin/app/dearnotes/model/`、`src/main/kotlin/app/dearnotes/Strings.kt` → `core/src/commonMain/kotlin/app/dearnotes/Strings.kt`
- Move: `src/test/kotlin/app/dearnotes/model/SortKeysTest.kt` → `core/src/commonTest/kotlin/app/dearnotes/model/`、`src/test/kotlin/app/dearnotes/StringsTest.kt` → `core/src/jvmTest/kotlin/app/dearnotes/`

**Interfaces:**
- Produces: Gradle プロジェクト `:core`。ルートは `implementation(project(":core"))`。`Strings.kt` の `isJavaWhitespace` / `isJavaBlank` / `strip` / `stripLeading` / `stripTrailing` は `public` にする（ルートの UI と DB から使うため）
- Produces: `checkCommonPurity` タスク（`:core` の `check` に含める）

- [ ] **Step 1: Gradle を組む**

- `settings.gradle.kts`: `include(":core")`
- ルートの `build.gradle.kts`: `plugins` に `kotlin("multiplatform") version "2.4.21" apply false` を足し、`dependencies` に `implementation(project(":core"))`
- `core/build.gradle.kts`: `kotlin("multiplatform")`。`kotlin { jvmToolchain(25); jvm(); compilerOptions { extraWarnings.set(true) } }`。`commonTest` に `kotlin("test")`。`jvmTest` は JUnit Platform で動かし、JUnit 6（ルートと同じ BOM）を使う

- [ ] **Step 2: `checkCommonPurity` を書く**

`core/src/commonMain` の `.kt` に、正規表現 `\b(java|javax)\.|\bSystem\.|\bCharacter\.|\bThread\.|@Synchronized` に当たる行があれば、ファイル名と行番号を出して失敗する Gradle タスク。ターゲットが JVM だけの間は、コンパイラが JDK の API を止めてくれないので、これで代わりに止める。`tasks.named("check") { dependsOn(...) }`。

- [ ] **Step 3: 型と補助を移す**

上の Move のとおり `git mv` する。`Strings.kt` の `internal` を `public` にする。`SortKeysTest` は JUnit の import を kotlin.test（`kotlin.test.Test`、`kotlin.test.assertEquals` など）に替える。`StringsTest` は JDK と比べるテストなので `jvmTest` に置き、JUnit のまま。

- [ ] **Step 4: 全体を通す**

Run: `./gradlew check`（ubuntu の CI と同じく、手元では画面のある状態で）
Expected: BUILD SUCCESSFUL。`checkCommonPurity` が走って通る。テストの件数は移す前と同じ 232

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: KMP の core モジュールを作り、モデルの型を移す"
```

### Task 8: `Ids` と `Page` を共通のコードにする

**Files:**
- Move: `src/main/kotlin/app/dearnotes/model/{Ids,Page}.kt` → `core/src/commonMain/kotlin/app/dearnotes/model/`
- Move: `src/test/kotlin/app/dearnotes/model/IdsTest.kt` → `core/src/jvmTest/kotlin/app/dearnotes/model/`
- Modify: `core/build.gradle.kts`（kotlinx-datetime を `commonMain` に）、`src/main/kotlin/app/dearnotes/db/PageRepo.kt:44,61,335`、`src/main/kotlin/app/dearnotes/ui/Sidebar.kt:97,310`、`src/main/kotlin/app/dearnotes/ui/MainWindow.kt:188`、`src/test/kotlin/app/dearnotes/db/PageRepoTest.kt`、`THIRD_PARTY_NOTICES.md`

**Interfaces:**
- Produces: `Ids.uuidV7(): String`（形と意味は今と同じ）、`Page.dailyDate: kotlinx.datetime.LocalDate?`、`PageRepo.openDaily(date: kotlinx.datetime.LocalDate): Page`

- [ ] **Step 1: 失敗するテストを書く（`IdsTest`、`PageRepoTest`）**

```kotlin
// IdsTest（jvmTest、JUnit のまま）
@Test
fun burstOverOneMillisecondCounterStaysOrderedAndUnique() {
    val ids = List(10_000) { Ids.uuidV7() }
    assertEquals(ids.sorted(), ids)
    assertEquals(ids.size, ids.toSet().size)
    ids.forEach { assertEquals(7, UUID.fromString(it).version()) }
}

@Test
fun concurrentCallersNeverCollide() {
    val all = ConcurrentHashMap.newKeySet<String>()
    val threads = List(8) { Thread { repeat(5_000) { all.add(Ids.uuidV7()) } } }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    assertEquals(40_000, all.size)
}
```

```kotlin
// PageRepoTest
@Test
fun dailyDateWrittenBy1xIsReadAsTheSameDay() {
    db.write { c -> c.createStatement().use { s ->
        s.executeUpdate("INSERT INTO pages (id, daily_date, sort_key, created_at, updated_at) VALUES ('d', '2026-10-11', 1, 0, 0)")
    } }
    assertEquals(LocalDate(2026, 10, 11), pages.get("d")!!.dailyDate)
}
```

既存の `PageRepoTest` の `LocalDate.of(2026, 10, 11)` は `LocalDate(2026, 10, 11)`（kotlinx-datetime）に替える。

- [ ] **Step 2: 走らせて、`PageRepoTest` がコンパイルで失敗することを確かめる**

Run: `./gradlew test`
Expected: `PageRepoTest` で `LocalDate(Int, Int, Int)` の型が合わずにコンパイルエラー（`Ids` の 2 つは今の実装でも通る。移したあとに同じく通ることを確かめるためのもの）

- [ ] **Step 3: `Ids` を共通のコードで書き直す**

状態を 1 つの `Long`（`ミリ秒 shl 12 or カウンタ`）にまとめ、`kotlin.concurrent.atomics.AtomicLong` の CAS ループで更新する。今の実装と同じ意味になる:

```kotlin
// prev = 前回の (lastMillis shl 12) or counter
val next = if (now > prev ushr 12) (now shl 12) or randomBits(11) else prev + 1
// prev + 1 は、カウンタが 0xFFF を超えるとミリ秒に繰り上がる（今の lastMillis++ と同じ）
```

時刻は `kotlin.time.Clock.System.now().toEpochMilliseconds()`。乱数（カウンタの初期値 11bit と、下位 62bit）は `kotlin.uuid.Uuid.random()` の値から取る（今の `SecureRandom` と同じく暗号論的な乱数にするため）。文字列は `Uuid.fromLongs(msb, lsb).toString()`。必要な `@OptIn` は付ける。

- [ ] **Step 4: `Page.dailyDate` を kotlinx-datetime の `LocalDate?` にし、使っている所を直す**

`PageRepo` の読み出しは `LocalDate.parse(daily)`（kotlinx）、書き込みは `date.toString()`（どちらも `YYYY-MM-DD`）。`Sidebar` と `MainWindow` の `LocalDate.now()` は `Clock.System.todayIn(TimeZone.currentSystemDefault())`。`THIRD_PARTY_NOTICES.md` に kotlinx-datetime（Apache License 2.0）の行を足す。

- [ ] **Step 5: 全体を通す**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL。テストは 235（232 + 3）

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: Ids と Page を core の共通コードに移す"
```

### Task 9: `editor` を移す

**Files:**
- Move: `src/main/kotlin/app/dearnotes/editor/*.kt`（8 ファイル）→ `core/src/commonMain/kotlin/app/dearnotes/editor/`
- Move: `src/test/kotlin/app/dearnotes/editor/*.kt` → `core/src/commonTest/kotlin/app/dearnotes/editor/`。ただし `MarkdownShortcutsTest` と `OutlineTest`（JUnit のパラメータ化テストを使う）は `core/src/jvmTest/kotlin/app/dearnotes/editor/`

**Interfaces:**
- Produces: 公開の名前と形は今と同じ。ルートから使っている `internal` があれば `public` にする（コンパイルエラーで分かる。それ以外は `internal` のまま）

- [ ] **Step 1: `git mv` で移す**

- [ ] **Step 2: JDK の API を置き換える**

- `Marks.kt`: `java.util.Comparator.nullsFirst` / `naturalOrder` / `Comparator.comparingInt` → `kotlin.comparisons` の `nullsFirst()` / `naturalOrder()` / `compareBy`
- `SlashCommands.kt`: `lowercase(Locale.ROOT)` → `lowercase()`（Kotlin の `lowercase()` はロケールに依存しない）

- [ ] **Step 3: `commonTest` に移したテストの import を kotlin.test に替える**

`org.junit.jupiter.api.Test` → `kotlin.test.Test`、`Assertions.assertEquals` / `assertNull` / `assertTrue` / `assertFalse` → `kotlin.test` の同名、`assertThrows(X::class.java) { }` → `assertFailsWith<X> { }`。

- [ ] **Step 4: 全体を通す**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL。テストは 235 のまま

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: editor を core の共通コードに移す"
```

### Task 10: `export` の純粋な部分を移す

**Files:**
- Move: `src/main/kotlin/app/dearnotes/export/{Inline,Links,MarkdownWriter}.kt` → `core/src/commonMain/kotlin/app/dearnotes/export/`
- Create: `core/src/commonMain/kotlin/app/dearnotes/export/CodePoints.kt`, `core/src/jvmMain/kotlin/app/dearnotes/export/CodePoints.jvm.kt`
- Move: `src/test/kotlin/app/dearnotes/export/{InlineTest,MarkdownWriterTest}.kt` → `core/src/commonTest/kotlin/app/dearnotes/export/`（`ExportTreeTest` はルートに残す）

**Interfaces:**
- Produces（`CodePoints.kt`、すべて `internal`）:
  - `fun String.firstCodePoint(): Int` / `fun String.lastCodePoint(): Int`（共通のコード。サロゲートペアを 1 つの符号位置にまとめる。`codePointAt(0)` / `codePointBefore(length)` の代わり）
  - `expect fun isSpaceSeparator(codePoint: Int): Boolean` / `expect fun isPunctuationOrSymbol(codePoint: Int): Boolean`（JVM の actual は今の `Character.getType` の判定をそのまま使う）

設計書は `Character.getType` を `Char.category` に替える例を挙げているが、`Char.category` は BMP の外の文字（絵文字は Unicode の「記号」）を判定できず、書き出しが変わる。そのため、符号位置で判定する `expect` / `actual` にする（Claude の判断）。

- [ ] **Step 1: 変更前の書き出しを期待値として固定するテストを書く（`InlineTest`）**

次の入力について、**変更前のコードで実際の出力を取り**、その値を期待値にしたテストを足す。

- 太字の直後に絵文字: `Inline.write("太字😀", listOf(Mark(0, 2, Style.BOLD)))`
- 太字の直前に BMP の外の記号（絵文字）: `Inline.write("😀太字", listOf(Mark(2, 4, Style.BOLD)))`
- 同じ装飾が何度も現れる（同じ値の `Delim` が並ぶ）: `Inline.write("a b a", listOf(Mark(0, 1, Style.BOLD), Mark(4, 5, Style.BOLD)))`
- 太字と斜体が重なり、記号の並びの長さが 3 の倍数の規則に当たる: `Inline.write("abc", listOf(Mark(0, 3, Style.BOLD), Mark(0, 1, Style.ITALIC)))`

- [ ] **Step 2: 変更前のコードで通ることを確かめる**

Run: `./gradlew test --tests 'app.dearnotes.export.InlineTest'`
Expected: PASS（期待値は変更前の出力そのもの）

- [ ] **Step 3: 移して、JDK の API を置き換える**

- `Pattern` → `Regex`。`matcher(s).matches()` → `matches(s)`、`replaceFirst("")` → `replaceFirst(s, "")`、グループの取り出しは `matchEntire(s)?.groupValues`
- `IdentityHashMap<Delim, Run>`: `Delim` は data class なので、同じ値の `Delim` を別のものとして扱う必要がある。普通の `HashMap` にはせず、トークンの添字（`Int`）をキーにした `HashMap<Int, Run>` にする
- `EnumMap` → `HashMap`、`EnumSet.noneOf` → `mutableSetOf()`、`TreeSet` → `mutableSetOf()` に集め、使う所で `sorted()`、`java.util.ArrayDeque` → `ArrayDeque`（`push` / `pop` は `addLast` / `removeLast`）、`java.util.Comparator { }` → `Comparator { }`（`kotlin.Comparator`）
- `codePointAt(0)` / `codePointBefore(length)` → `firstCodePoint()` / `lastCodePoint()`、`Character.getType` の判定 → `isSpaceSeparator` / `isPunctuationOrSymbol`

- [ ] **Step 4: `commonTest` に移したテストの import を kotlin.test に替える（Task 9 と同じ規則）**

- [ ] **Step 5: 全体を通す**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL。テストは 239（235 + 4）。Step 1 のテストが移したあとも通る

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: export の純粋な部分を core の共通コードに移す"
```

### Task 11: 配布物を確かめ、PR を出す

**Files:**
- Modify: `README.md`（「動かす」に、ロジックが `core/` にあることを 1 行）、`CONTRIBUTING.md`（ソースの場所の説明があれば）

- [ ] **Step 1: アプリを起動して、ふだんの操作を確かめる**

Run: `./gradlew run -Pdearnotes.dataDir=/tmp/dn-core`
Expected: 窓が開く。ページを作り、見出し・箇条書き・太字・ページリンクを入れ、今日の日記を開き、検索し、Markdown に書き出せる

- [ ] **Step 2: インストーラーを作り、`:core` が入っていることを確かめる**

Run: `./gradlew jpackage && ls build/jpackage-input`
Expected: `build/jpackage-input` に `dearnotes.jar` と `core-jvm`（`:core` の jar）と `kotlinx-datetime` の jar がある。macOS では `build/jpackage/DearNotes-1.3.2.dmg` ができ、入れたアプリが起動してページを開ける

- [ ] **Step 3: Commit と PR**

```bash
git add README.md CONTRIBUTING.md
git commit -m "docs: core モジュールの場所を書く"
git push
gh pr create --base main --title "refactor: 純粋なロジックを KMP の core モジュールに移す"
```

PR の本文には、移したもの、`Character.getType` を `expect` / `actual` にした理由、`checkCommonPurity` の役割、3 つの OS の CI の結果を書く。
