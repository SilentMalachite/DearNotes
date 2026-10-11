# ページ間リンク 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `[[` で別のページへのリンクを入れ、Cmd/Ctrl+クリックで移り、ページの下にバックリンクを出し、子ページごとの書き出しでは相対パスのリンクにする。

**Architecture:** リンクは今の装飾 `Mark` に `PAGE` の種類と `target`（ページの id）を足して表す。DB の表は変えず、`marks` 列の JSON の4つ目に id を入れる。画面では RichTextFX の CSS クラス `m-page` と `to-<id>` で持つ。バックリンクは `marks` を LIKE で探す。

**Tech Stack:** Java 25、JavaFX、RichTextFX 0.11.7、SQLite（sqlite-jdbc）、JUnit 5

**Spec:** `docs/plan/page-links.html`（決定: 新しいページを作る=作る、改名時のリンクの文字=そのまま、バックリンク=作る、書き出し=`[京都](../京都.md)`）

## Global Constraints

- DB の表とマイグレーションは変えない（`Migrations.STEPS` に足さない）
- marks の JSON: `[[start,end,"style"]]`、PAGE だけ `[[start,end,"page","<id>"]]`
- 古い版は要素が3つでない装飾を捨てる（`MarksCodec.decode`）。文字は残る
- URL のリンク（Cmd/Ctrl+Shift+L、`Mark.Style.LINK`）の動きは変えない
- 貼り付けた `[[…]]` や `[a](b.md)` はリンクにしない
- コメントと UI の文言は日本語。既存のコードの書き方（短い Javadoc、`Errors.get` で DB を呼ぶ）に合わせる
- コミットは `<type>: <説明>`（日本語）

## Review Focus

1. **リンクの末尾で続けて打つ**: RichTextFX は前の文字の装飾を引き継ぐ。末尾で打った文字はリンクにならず、リンクの途中で打った文字はリンクのまま（Task 2 の UI テスト）
2. **日本語入力の `「「`**: `[[` と同じ候補が出る（Task 3 のテスト）
3. **ゴミ箱のページ・消えたページへのリンク**: 押すと知らせて、移らない（Task 5 の UI テスト）
4. **隣り合う別のページへのリンク**: `Marks.normalize` が1つにまとめない（Task 1 のテスト）
5. **ファイル名に空白・括弧・# があるページへのリンク**: 書き出したリンクが壊れない（Task 7 のテスト）

---

### Task 1: Mark にリンク先を持たせる

**Files:**
- Modify: `src/main/java/app/dearnotes/model/Mark.java`
- Modify: `src/main/java/app/dearnotes/editor/Marks.java`
- Modify: `src/main/java/app/dearnotes/editor/MarksCodec.java`
- Test: `src/test/java/app/dearnotes/editor/MarksTest.java`, `MarksCodecTest.java`

**Interfaces:**
- Produces: `Mark(int start, int end, Style style, String target)`、`Mark(int, int, Style)`（target は null）、`Mark.Style.PAGE`、`Mark withRange(int start, int end)`

- [ ] **Step 1: 失敗するテストを書く**

```java
// MarksCodecTest
@Test
void pageLinkKeepsItsTarget() {
    var marks = List.of(new Mark(2, 4, Style.PAGE, "0192f3c1-aaaa"));
    assertEquals("[[2,4,\"page\",\"0192f3c1-aaaa\"]]", MarksCodec.encode(marks));
    assertEquals(marks, MarksCodec.decode(MarksCodec.encode(marks)));
}

@Test
void pageLinkWithoutTargetIsDropped() {
    assertEquals(List.of(new Mark(0, 1, Style.BOLD)), MarksCodec.decode("[[0,1,\"bold\"],[2,4,\"page\"]]"));
}

// MarksTest
@Test
void adjacentLinksToDifferentPagesStaySeparate() {
    var a = new Mark(0, 2, Style.PAGE, "a");
    var b = new Mark(2, 4, Style.PAGE, "b");
    assertEquals(List.of(a, b), Marks.normalize(List.of(b, a)));
    assertEquals(List.of(new Mark(0, 4, Style.PAGE, "a")),
        Marks.normalize(List.of(a, new Mark(2, 4, Style.PAGE, "a"))));
}

@Test
void sliceAndConcatKeepTheTarget() {
    var link = new Mark(1, 5, Style.PAGE, "p");
    assertEquals(List.of(new Mark(0, 2, Style.PAGE, "p")), Marks.slice(List.of(link), 3, 8));
    assertEquals(List.of(new Mark(4, 8, Style.PAGE, "p")), Marks.concat(List.of(), 3, List.of(link)));
}
```

- [ ] **Step 2:** `./gradlew test --tests '*Marks*'` → コンパイルエラー（PAGE がない）
- [ ] **Step 3: 実装**

```java
// Mark.java
/** 行内の装飾。start は含み、end は含まない。target は PAGE のときだけ、リンク先のページの id。 */
public record Mark(int start, int end, Style style, String target) {
    public enum Style { BOLD, ITALIC, STRIKE, CODE, LINK, PAGE; … }
    public Mark {
        if (start < 0 || end < start) throw new IllegalArgumentException("bad range: " + start + ".." + end);
        if ((style == Style.PAGE) != (target != null && !target.isBlank())) {
            throw new IllegalArgumentException("bad target: " + style + " " + target);
        }
    }
    public Mark(int start, int end, Style style) { this(start, end, style, null); }
    /** 種類とリンク先はそのままで、範囲を替える。 */
    public Mark withRange(int start, int end) { return new Mark(start, end, style, target); }
}
```

`Marks`: `slice`/`concat` は `m.withRange(...)`。`normalize` は `style` → `target`（null が先）→ `start` で並べ、`style` と `target` が同じときだけまとめる。最後の並びも `start` → `style` → `target`。

`MarksCodec.encode`: target があれば `,"<id>"` を足す。`decode`: 要素が3つか4つなら読む。4つ目は target。

- [ ] **Step 4:** `./gradlew test` → 全部 PASS
- [ ] **Step 5:** `git commit -m "feat: 装飾にページへのリンクの種類を足す"`

### Task 2: 画面でリンクを持つ（StyledText と BlockView）

**Files:**
- Modify: `src/main/java/app/dearnotes/ui/StyledText.java`
- Modify: `src/main/java/app/dearnotes/ui/BlockView.java`
- Modify: `src/main/resources/app/dearnotes/ui/dearnotes.css`
- Test: `src/test/java/app/dearnotes/ui/PageEditorUiTest.java`

**Interfaces:**
- Consumes: Task 1 の `Mark`
- Produces: `StyledText.pageClasses(String target)`, `StyledText.targetOf(Collection<String>)`, `StyledText.withoutPage(Collection<String>)`、`BlockView.insertPageLink(int from, int to, String name, String target)`、`BlockView.Host` に `openPage(String pageId)`, `linkQuery(BlockView v)`, `blurred(BlockView v)`（`slashQuery(v, null)` の代わりにフォーカスが外れたとき呼ぶ）

- [ ] **Step 1: 失敗するテストを書く**

```java
@Test
void pageLinkSurvivesLoadAndRead() throws Exception {
    var link = List.of(new Mark(3, 5, Mark.Style.PAGE, "p1"), new Mark(5, 7, Mark.Style.PAGE, "p2"));
    List<Mark> marks = fx(() -> {
        var area = new org.fxmisc.richtext.StyleClassedTextArea();
        StyledText.load(area, "次は京都大阪へ", link);
        return StyledText.read(area);
    });
    assertEquals(link, marks);
}

@Test
void typingAtTheEndOfPageLinkIsNotLinked() throws Exception {
    var p = pages.create(null, "リンク");
    blocks.save(List.of(Block.empty(p.id(), BlockType.PARAGRAPH, 1024)
        .withContent("京都", List.of(new Mark(0, 2, Mark.Style.PAGE, "x")))), List.of());
    reopen(p.id());
    focusEndOf(0);
    type("へ");
    fx(() -> { current().area().moveTo(1); return null; });
    type("の");
    assertEquals(List.of(new Mark(0, 3, Mark.Style.PAGE, "x")), fx(() -> current().block().marks()));
    assertEquals("京の都へ", fx(() -> current().block().text()));
}
```

- [ ] **Step 2:** `./gradlew test --tests '*PageEditorUiTest*'` → FAIL
- [ ] **Step 3: 実装**
  - `StyledText`: 定数 `TARGET = "to-"`。`spans()` は target があれば `to-<id>` も付ける。`read()` は span の `to-` クラスを target にして Mark を作る（PAGE 以外は null）
  - `BlockView.plainTextChanges` に `unlinkTail(at, length)` を足す。前の文字と打った文字が同じリンク先で、後ろの文字が同じリンク先でなければ、打った範囲から `m-page` と `to-*` を外す
  - `onClicked`: PAGE なら `host.openPage(m.target())`
  - `onEdited`: `host.linkQuery(this)` を呼ぶ。フォーカスが外れたら `host.blurred(this)`
  - CSS: `.styled-text-area .m-page { -fx-fill: -dn-accent; -fx-underline: true; }`
  - PageEditor に Host の3つを空で足す（中身は Task 5）
- [ ] **Step 4:** `./gradlew test` → PASS
- [ ] **Step 5:** `git commit -m "feat: ページへのリンクを画面で持ち、末尾の入力をリンクにしない"`

### Task 3: `[[` の語句を読む（PageLinks）

**Files:**
- Create: `src/main/java/app/dearnotes/editor/PageLinks.java`
- Test: `src/test/java/app/dearnotes/editor/PageLinksTest.java`

**Interfaces:**
- Produces: `record PageLinks.Query(int from, String text)`、`static Optional<Query> PageLinks.queryAt(String text, int caret)`

- [ ] **Step 1: テスト**

```java
@Test void findsQueryAfterBrackets() { assertEquals(Optional.of(new Query(3, "京")), PageLinks.queryAt("次は [[京", 6)); }
@Test void japaneseBracketsWork() {
    assertEquals(Optional.of(new Query(0, "京都")), PageLinks.queryAt("「「京都", 4));
    assertEquals(Optional.of(new Query(0, "")), PageLinks.queryAt("［［", 2));
}
@Test void emptyQueryRightAfterBrackets() { assertEquals(Optional.of(new Query(1, "")), PageLinks.queryAt("a[[", 3)); }
@Test void looksOnlyBeforeCaret() { assertEquals(Optional.of(new Query(0, "京都")), PageLinks.queryAt("[[京都 ab", 4)); }
@Test void closedOrBrokenQueryIsNotAQuery() {
    assertEquals(Optional.empty(), PageLinks.queryAt("[[京都]] へ", 7));
    assertEquals(Optional.empty(), PageLinks.queryAt("[[京\n都", 5));
    assertEquals(Optional.empty(), PageLinks.queryAt("[京", 2));
}
```

- [ ] **Step 2:** FAIL（クラスがない）
- [ ] **Step 3:** 正規表現 `(?:\[\[|「「|［［)([^\[\]「」［］\n]{0,40})$` を caret までの文字に当てる
- [ ] **Step 4:** PASS
- [ ] **Step 5:** `git commit -m "feat: [[ の後ろのページ名を読む"`

### Task 4: 名前でページを探す（PageRepo.findByTitle）

**Files:**
- Modify: `src/main/java/app/dearnotes/db/PageRepo.java`
- Test: `src/test/java/app/dearnotes/db/PageRepoTest.java`

**Interfaces:**
- Produces: `List<Page> PageRepo.findByTitle(String query, int limit)`

- [ ] **Step 1: テスト**

```java
@Test
void findByTitlePutsPrefixMatchesFirstAndSkipsTrashAndUntitled() throws Exception {
    var inn = pages.create(null, "東京の宿");
    var kyoto = pages.create(null, "京都");
    var gone = pages.create(null, "京都メモ");
    pages.create(null, "");
    pages.trash(gone.id(), 1);
    assertEquals(List.of(kyoto.id(), inn.id()), pages.findByTitle("京", 8).stream().map(Page::id).toList());
    assertEquals(2, pages.findByTitle("", 8).size());
    assertEquals(List.of(), pages.findByTitle("100%", 8));
}
```

- [ ] **Step 2:** FAIL
- [ ] **Step 3:** `WHERE deleted_at IS NULL AND title <> '' AND title LIKE ? ESCAPE '\'`、`ORDER BY instr(title, ?) = 1 DESC, updated_at DESC LIMIT n`。`SearchRepo.escapeLike` を使う
- [ ] **Step 4:** PASS
- [ ] **Step 5:** `git commit -m "feat: 名前でページを探す"`

### Task 5: 候補の窓、リンクを入れる、リンク先へ移る

**Files:**
- Create: `src/main/java/app/dearnotes/ui/PageLinkMenu.java`
- Modify: `src/main/java/app/dearnotes/ui/PageEditor.java`, `MainWindow.java`
- Test: `src/test/java/app/dearnotes/ui/PageEditorUiTest.java`

**Interfaces:**
- Consumes: Task 2〜4
- Produces: `PageEditor.Navigator { void pageChanged(Page p); void openPage(String pageId, String blockId); Optional<Page> newLinkedPage(String parentId, String title); }`（MainWindow が実装）、`new PageEditor(Services, Navigator)`、`PageLinkMenu.Choice(Page page, String name)`

- [ ] **Step 1: テスト**

```java
@Test
void typingBracketsAndEnterInsertsPageLink() throws Exception {
    var kyoto = pages.create(null, "京都");
    focusFirstBlock();
    type("次は「「京");
    press(KeyCode.ENTER);
    type("へ");
    assertEquals("次は京都へ", fx(() -> current().block().text()));
    assertEquals(List.of(new Mark(2, 4, Mark.Style.PAGE, kyoto.id())), fx(() -> current().block().marks()));
}

@Test
void unknownNameCreatesChildPage() throws Exception {
    focusFirstBlock();
    String here = fx(() -> window.editor().page().id());
    type("[[奈良");
    press(KeyCode.ENTER);
    var nara = pages.findByTitle("奈良", 8).getFirst();
    assertEquals(here, nara.parentId());
    assertEquals(here, fx(() -> window.editor().page().id()));   // 今のページに残る
    assertEquals(List.of(new Mark(0, 2, Mark.Style.PAGE, nara.id())), fx(() -> current().block().marks()));
}

@Test
void openingPageLinkMovesToItOrWarns() throws Exception {
    var kyoto = pages.create(null, "京都");
    fx(() -> { window.openPage(kyoto.id(), null); return null; });
    settle();
    assertEquals(kyoto.id(), fx(() -> window.editor().page().id()));
    String before = kyoto.id();
    pages.trash(kyoto.id(), 1);
    var other = pages.create(null, "別");
    fx(() -> { window.open(other); return null; });
    settle();
    fx(() -> { window.openPage(before, null); return null; });   // ゴミ箱: 移らない
    settle();
    assertEquals(other.id(), fx(() -> window.editor().page().id()));
}
```

- [ ] **Step 2:** FAIL
- [ ] **Step 3: 実装**
  - `PageLinkMenu`: `SlashMenu` と同じ作り（Popup + ListView、↑↓ Enter Tab Esc）。行は「アイコン 名前」、新規は「＋ 新しいページ「x」を作る」
  - `PageEditor.linkQuery(v)`: `Platform.runLater` で `PageLinks.queryAt(v.text(), v.caret())` を読み、CODE なら閉じる。`findByTitle(q, 8)`、同じ名前がなく q が空でなければ新規の行を足す
  - `slashKey`: `links.handleKey(v, e) || slash.handleKey(v, e)`
  - 選んだら `Navigator.newLinkedPage(page.id(), name)`（新規のときだけ）→ `v.insertPageLink(from, caret, name, id)`
  - `MainWindow.openPage(id, blockId)`: ない → `Errors.warn("リンク先のページがありません", …)`、ゴミ箱 → `Errors.warn("「名前」はゴミ箱にあります", …)`、ほか → `open(p, blockId)`
  - `MainWindow.newLinkedPage`: `PageRepo.create(parentId, title)`、`sidebar.reload(今のページ)`
- [ ] **Step 4:** PASS
- [ ] **Step 5:** `git commit -m "feat: [[ でページへのリンクを入れ、Cmd/Ctrl+クリックで移る"`

### Task 6: このページへのリンク（バックリンク）

**Files:**
- Modify: `src/main/java/app/dearnotes/db/BlockRepo.java`
- Create: `src/main/java/app/dearnotes/ui/Backlinks.java`
- Modify: `src/main/java/app/dearnotes/ui/PageEditor.java`, `dearnotes.css`
- Test: `src/test/java/app/dearnotes/db/BlockRepoTest.java`, `PageEditorUiTest.java`

**Interfaces:**
- Produces: `record BlockRepo.Backlink(String pageId, String pageTitle, String pageIcon, boolean daily, String blockId, String text)`、`List<Backlink> BlockRepo.linksTo(String target)`、`Backlinks.show(List<Backlink>)`

- [ ] **Step 1: テスト**

```java
// BlockRepoTest
@Test
void linksToFindsBlocksOnAlivePagesOnly() throws Exception {
    var kyoto = pages.create(null, "京都");
    var trip = pages.create(null, "旅行");
    var old = pages.create(null, "古い");
    var link = List.of(new Mark(2, 4, Mark.Style.PAGE, kyoto.id()));
    var b = Block.empty(trip.id(), BlockType.PARAGRAPH, 1).withContent("次は京都へ", link);
    blocks.save(List.of(b,
        Block.empty(old.id(), BlockType.PARAGRAPH, 1).withContent("次は京都へ", link),
        Block.empty(kyoto.id(), BlockType.PARAGRAPH, 1).withContent("自分", List.of(new Mark(0, 2, Mark.Style.PAGE, kyoto.id())))),
        List.of());
    pages.trash(old.id(), 1);
    var hits = blocks.linksTo(kyoto.id());
    assertEquals(List.of(b.id()), hits.stream().map(BlockRepo.Backlink::blockId).toList());
    assertEquals("旅行", hits.getFirst().pageTitle());
}

// PageEditorUiTest
@Test
void backlinksListPagesThatLinkHere() throws Exception {
    var kyoto = pages.create(null, "京都");
    var trip = pages.create(null, "旅行");
    blocks.save(List.of(Block.empty(trip.id(), BlockType.PARAGRAPH, 1)
        .withContent("次は京都へ", List.of(new Mark(2, 4, Mark.Style.PAGE, kyoto.id())))), List.of());
    reopen(kyoto.id());
    assertEquals(List.of("📝 旅行"), fx(() -> window.editor().backlinks().titles()));
}
```

- [ ] **Step 2:** FAIL
- [ ] **Step 3:** `linksTo`: `blocks JOIN pages`、`b.marks LIKE '%"page","<id>"]%'`、`p.deleted_at IS NULL AND p.id <> ?`、`ORDER BY p.updated_at DESC, b.sort_key LIMIT 50`。`Backlinks`（VBox）: 見出し「このページへのリンク · n」と行。行を押すと `Navigator.openPage(pageId, blockId)`。空なら隠す。`PageEditor.open()` の最後で読み直す
- [ ] **Step 4:** PASS
- [ ] **Step 5:** `git commit -m "feat: このページへのリンクをページの下に出す"`

### Task 7: 書き出しで相対パスのリンクにする

**Files:**
- Create: `src/main/java/app/dearnotes/export/Links.java`
- Modify: `src/main/java/app/dearnotes/export/Inline.java`, `MarkdownWriter.java`, `ExportTree.java`
- Test: `src/test/java/app/dearnotes/export/InlineTest.java`, `ExportTreeTest.java`

**Interfaces:**
- Produces: `interface Links { String href(String pageId); Links NONE; }`、`Inline.write(String, List<Mark>, Links)`、`MarkdownWriter.write(Page, List<Block>, Links)`（2引数の版は `Links.NONE`）

- [ ] **Step 1: テスト**

```java
// InlineTest
@Test
void pageLinkBecomesRelativeLinkOrPlainText() {
    var marks = List.of(new Mark(2, 4, PAGE, "k"));
    assertEquals("次は[京都](../京都.md)へ", Inline.write("次は京都へ", marks, id -> "../京都.md"));
    assertEquals("次は京都へ", Inline.write("次は京都へ", marks, Links.NONE));
    assertEquals("[a\\*b](a%20%28b%29%20%2325%25.md)",
        Inline.write("a*b", List.of(new Mark(0, 3, PAGE, "k")), id -> "a (b) #25%.md"));
    assertEquals("\\![京都](京都.md)", Inline.write("!京都", List.of(new Mark(1, 3, PAGE, "k")), id -> "京都.md"));
}

// ExportTreeTest
@Test
void linksBetweenExportedPagesBecomeRelativePaths() throws Exception {
    var trip = page("t", null, "旅行", 0);
    var kyoto = page("k", "t", "京都", 1);
    var osaka = page("o", "t", "大阪", 2);
    var day1 = page("d", "o", "1日目", 0);
    var books = page("b", null, "読書メモ", 3);
    var linkTo = (java.util.function.BiFunction<String, String, Block>) (text, target) ->
        Block.empty("x", BlockType.PARAGRAPH, 0).withContent(text, List.of(new Mark(0, text.length(), Mark.Style.PAGE, target)));
    Map<String, List<Block>> bodies = Map.of(
        "t", List.of(linkTo.apply("京都", "k")),
        "d", List.of(linkTo.apply("京都", "k"), linkTo.apply("読書メモ", "b")));
    ExportTree.write(dir, trip, List.of(trip, kyoto, osaka, day1, books), id -> bodies.getOrDefault(id, List.of()));
    assertTrue(read("旅行/旅行.md").contains("[京都](京都.md)"));
    String d = read("旅行/大阪/1日目.md");
    assertTrue(d.contains("[京都](../京都.md)"));
    assertTrue(d.contains("\n読書メモ\n"));
}
```

- [ ] **Step 2:** FAIL
- [ ] **Step 3:** `Inline` は PAGE を LINK と同じ「途中で切らない」ものとして扱い、`links.href(target)` があれば `[文字](パス)`、なければ文字。パスは `%`→`%25`、空白→`%20`、`(`→`%28`、`)`→`%29`、`#`→`%23`。`ExportTree.write` は先に `plan()` で id → ファイルの表を作ってから書く。リンクは書くファイルのフォルダからの相対パスを `/` でつなぐ
- [ ] **Step 4:** `./gradlew test` → PASS
- [ ] **Step 5:** `git commit -m "feat: 子ページごとの書き出しでページ間リンクを相対パスにする"`

### Task 8: 説明書き

**Files:**
- Modify: `README.md`（できること）、`CHANGELOG.md`（`[Unreleased]` の「追加」）

- [ ] **Step 1:** README の「できること」に1行: `[[` を打つと（日本語入力の `「「` でも）ページの候補が出る。Cmd/Ctrl を押しながらクリックで移る。ページの下にこのページへのリンクが並ぶ
- [ ] **Step 2:** CHANGELOG の `[Unreleased]` に `### 追加` と同じ内容
- [ ] **Step 3:** `./gradlew test` → PASS
- [ ] **Step 4:** `git commit -m "docs: ページ間リンクを README と CHANGELOG に書く"`

## 設計ページから変えたところ

- 書き出しのパスの空白や括弧は `<…>` で囲まず、今の `Inline.link()` と同じくパーセント符号にする
- 候補の窓の行に親ページの名前は出さない（アイコンと名前だけ）
