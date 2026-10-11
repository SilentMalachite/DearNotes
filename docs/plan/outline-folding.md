# アウトラインと折りたたみ 実装計画

設計は `docs/plan/outline-folding.html` に従う。決定事項は次の4つ。

| 論点 | 決定 |
|---|---|
| 目次の場所 | 本文の右のパネル。初めは閉じておく |
| 畳む・開くキー | Cmd/Ctrl+Enter（▸▾ を押しても同じ） |
| 畳める行 | 子の行を持つ行すべて（見出し、箇条書き、ToDo、段落など） |
| 畳んだ状態 | DB の `blocks.collapsed` に保存する |

## 範囲の決め方（`editor/Outline`）

UI から切り離し、`List<Block>` だけを受け取る純粋な関数にする。

- 見出し（H1〜H3）の範囲: 次の「字下げが自分以下で、同じか上の段の見出し」か「字下げが自分より浅い行」の手前まで（範囲どうしが交わらないようにするため）
- それ以外の行の範囲: 字下げが自分より深い行が続くところまで
- 子の行がある = 範囲に自分以外の行がある。▸▾ はこの行にだけ出す
- 隠れる行 = 畳んだ行の範囲に入る行（範囲の和）
- 目次 = H1〜H3 の一覧（段で字下げ）。今いる節 = カーソルの行より前で最も近い見出し

## 変えるもの

### 1. モデルと DB
- `Block` に `collapsed` を足す（最後の引数）。8引数のコンストラクタも残し、`collapsed = false` にする。`withCollapsed` を足す
- `Migrations` v2: `ALTER TABLE blocks ADD COLUMN collapsed INTEGER NOT NULL DEFAULT 0`。種類は足さないので表の作り直しも FTS のトリガーの作り直しもいらない
- `BlockRepo`: `collapsed` を読み、書く（INSERT のパラメータ 9〜11 がずれる）

### 2. `editor/Outline`, `BlockOps`
- `Outline.end / hasChildren / hidden / hiders / headings / currentHeading`
- `BlockOps.keysForMove(blocks, from, count, insertAt)`: 連続する count 行をまとめて動かすキー。間がなければ null

### 3. `BlockView`
- 字下げの右に ▸▾ の枠（幅は常に取る）。押すと `Host.toggleFold`
- Cmd/Ctrl+Enter で `Host.toggleFold`
- `setFoldable`, `setHidden`。CSS クラス `foldable` / `collapsed`。▾ はマウスを載せたときだけ、▸ はいつも見せる

### 4. `PageEditor`
- `refreshNumbers()` を `refreshLayout()` にする: 番号、子のない行の畳んだ印を外す、隠す行と ▸▾ を反映、目次へ知らせる
- `refreshLayout()` は、見えていた行（足したばかりの行を含む）が畳んだ範囲に入ったら、その範囲を開いて見えたままにする。行を足す、種類や字下げを変える、動かす、消す、貼るのどれでも、見えていた本文が知らせなしに消えないようにするため。畳む操作とページを開いたときだけこれをしない
- `reveal(v)`: v を隠している畳んだ行をすべて開く。次のときに呼ぶ
  - Backspace で前の行が隠れているとき、Delete で次の行が隠れているとき（結合の前に開く）
  - 検索結果や目次から行へ移るとき
- 見出しと見出しでない種類を行き来するとき、畳んだ印を外す（範囲の決め方が変わるため）
- Enter: 畳んだ行の末尾側で分けたときは、隠れた行の後に新しい行を入れる。畳んだ見出しのときは同じ段の見出しにする（段落だとその節に入って隠れてしまうため）
- ↑↓←→ は隠れた行を飛ばす
- Cmd/Ctrl+Shift+↑↓ とドラッグ: 畳んだ行は隠れた行と一緒に動く。隣の畳んだ行のまとまりは飛び越える
- Tab / Shift+Tab: 畳んだ見出しでない行は、隠れた行も一緒に字下げを変える
- 目次用に `headings()`, `currentHeadingId()`, `jumpTo(blockId)`, `setOutlineListener(Runnable)`

### 5. `OutlinePanel`（新規）と `MainWindow`
- `OutlinePanel`: 「目次」の見出しと項目の一覧。段ごとに字下げし、今いる節を太字にする。見出しがなければ「見出しがありません」
- `MainWindow`: 本文の右に置き、初めは閉じておく。ヘッダーの「☰ 目次」ボタンと Cmd/Ctrl+Shift+O で出し入れする

## テスト
- `OutlineTest`: 見出しの節、字下げの範囲、隠れる行、目次、今いる節
- `BlockOpsTest`: `keysForMove`
- `BlockRepoTest`: `collapsed` の保存と読み込み
- `PageEditorUiTest`: Cmd+Enter で畳む・開く、開き直しても残る、↓ が隠れた行を飛ばす、Enter が隠れた行の後に入る、畳んだ行をまとめて動かす、目次から移ると開く、子をなくすと印が外れる、動かす・空の見出しを消す・Shift+Tab で見えていた行が隠れない

## 変えないもの
サイドバーのページ一覧、検索と FTS（隠れた行も見つける）、`PasteParser`（貼った行は開いた状態）、番号の付け方（隠れた行も数える）、ブロックの種類。
