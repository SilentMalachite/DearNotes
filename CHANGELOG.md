# 変更履歴

このプロジェクトの主な変更を記録します。書き方は [Keep a Changelog](https://keepachangelog.com/ja/1.1.0/) に、版の付け方は [Semantic Versioning](https://semver.org/lang/ja/) に従います。

## [Unreleased]

## [1.1.0] - 2026-10-11

### 追加

- Apache License 2.0 で公開（`LICENSE`、`NOTICE`）
- サードパーティのライセンス一覧、変更履歴、貢献ガイド、セキュリティポリシー
- タグを push すると、インストーラーを付けた GitHub Release を自動で作る（本文は CHANGELOG.md から）
- 目次: 本文の右に H1〜H3 の一覧を出す。「☰ 目次」ボタンか Cmd/Ctrl+Shift+O で出し入れし、押すとその見出しへ移る
- 折りたたみ: 子の行を持つ行（見出しの節、字下げした項目）を ▸▾ か Cmd/Ctrl+Enter で畳む・開く。畳んだ状態はページを開き直しても残る

## [1.0.1] - 2026-10-11

### 修正

- 保存に失敗しているあいだにページを切り替えると、まだ保存していない編集が消えることがある
- 複数行を貼り付けて最後が区切り線になると、カーソルより後ろの本文が見えなくなる
- サイドバーでページを先頭・末尾へドラッグすると失敗する
- 「今日」でゴミ箱から日記を戻すとき、一緒に削除した子ページが戻らない
- コードブロックで「/」を打って Enter すると、本文が消えて普通の段落になる
- 検索語を変えてすぐ Enter したり結果を押したりすると、変更前の結果を開く
- Shift+↑↓ で範囲選択できず、隣のブロックへ移ってしまう
- 保存できずページを切り替えられないときでも、新しいページや今日の日記が作られる
- ページの読み込みに失敗すると、空の画面のまま開いたことになる

### 変更

- Intel Mac をサポート外にし、macOS のインストーラーは Apple Silicon 向けだけにした

## [1.0.0] - 2026-10-11

最初の版です。

### 追加

- 10種類のブロック（本文、見出し1〜3、箇条書き、番号付き、ToDo、引用、コード、区切り線）
- 行頭の Markdown 記号で種類を変えるショートカット（全角の記号も可）と「/」メニュー
- ブロックの分割・結合・字下げ・ドラッグでの並べ替え、行内の装飾、Markdown の貼り付け
- 入れ子のページ、日記、ゴミ箱（30日で自動削除）
- SQLite FTS5 の trigram による日本語の部分一致検索（Cmd/Ctrl+K）
- 入力が 500ms 止まったら1本の書き込みスレッドで保存する自動保存と、終了時の書き出し
- jlink と jpackage による macOS（.dmg）と Windows（.msi）のインストーラー

[Unreleased]: https://github.com/SilentMalachite/DearNotes/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/SilentMalachite/DearNotes/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/SilentMalachite/DearNotes/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/SilentMalachite/DearNotes/releases/tag/v1.0.0
