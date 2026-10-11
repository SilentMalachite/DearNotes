# 貢献ガイド

DearNotes への不具合の報告や修正の提案を歓迎します。

## 不具合の報告と要望

[Issues](https://github.com/SilentMalachite/DearNotes/issues) に書いてください。不具合なら、次があると直しやすくなります。

- OS と版（例: macOS 26.0 / Apple Silicon、Windows 11）
- DearNotes の版（インストーラーの名前か、`build.gradle.kts` の `version`）
- 何をしたら、どうなったか。本当はどうなってほしかったか
- 日本語入力のときだけ起きるかどうか

セキュリティの問題は Issues に書かず、[SECURITY.md](SECURITY.md) の方法で知らせてください。

[README](README.md) の「v1 に入れないもの」に挙げた機能（クラウド同期、添付ファイル、表など）は、いまは入れる予定がありません。

## 開発の準備

JDK 25 が要ります。JavaFX は Gradle が取ってきます。

```sh
./gradlew run     # 起動
./gradlew test    # テスト
```

ふだん使っているデータを壊さないよう、試すときは別のデータフォルダを使ってください。

```sh
./gradlew run -Pdearnotes.dataDir=/tmp/dn-test
```

コードの構成は README の「構成」に、設計は `docs/plan/` にあります。

## 変更を送る

1. `main` から作業用のブランチを作る（例: `fix/search-enter`、`feat/table-block`）
2. 直したい動きを再現するテストを先に書き、落ちることを確かめてから直す
3. `./gradlew test` がすべて通ることを確かめる。コンパイルの警告も出さない
4. 利用者に見える変更なら、[CHANGELOG.md](CHANGELOG.md) の `[Unreleased]` に1行足す
5. Pull Request を `main` に向けて出す。何を、なぜ変えたかと、どう確かめたかを書く

CI（GitHub Actions）で同じテストが動きます。通らない Pull Request はマージしません。

### テスト

- 画面の動きは `src/test/java/app/dearnotes/ui/PageEditorUiTest.java` のように、本物の画面にキーイベントを送って確かめる。画面のない環境では UI テストは飛ぶ
- データベースのテストは `DbTestBase` を継いで、一時フォルダの SQLite で動かす

### コードの書き方

- まわりのコードに合わせる。コメントは日本語で、何をしているかより、なぜそうするかを短く書く
- 保存は `AutoSaver` の書き込みスレッドを通す。UI のスレッドで重い処理をしない
- 新しい依存を足したら、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) にも書く

### コミットメッセージ

```
<種類>: <何をしたか>

<なぜそうしたか（任意）>
```

種類は `feat` `fix` `refactor` `docs` `test` `chore` `perf` `ci` のどれかです。本文は日本語で書きます。

## ライセンス

送ってもらった変更は、このプロジェクトと同じ [Apache License 2.0](LICENSE) で公開します（ライセンスの第5条）。
