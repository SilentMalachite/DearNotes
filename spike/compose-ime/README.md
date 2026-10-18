# Compose 日本語入力の試作（使い捨て）

main には入れない。点検表は main の `docs/plan/ime-checklist.md`。

## 起動

```sh
git fetch origin spike/compose-ime
git switch spike/compose-ime
cd spike/compose-ime
./gradlew run          # Temurin 25（点検表の記録 2）
./gradlew -Pjbr run    # JetBrains Runtime 25（記録 3。初回は自動で取ってくる）
```

Windows では `gradlew.bat run` / `gradlew.bat -Pjbr run`。窓のタイトルに、使っている実行環境が出る。

## 画面

- 1 行目は「あいうえお」で、「いう」が太字（点検表の 6）
- 上のチェックを入れると、確定の直後（50ms 以内）に届く Enter を捨てる。今の JavaFX 版の `ImeGuard` と同じ考え方。点検表の 2・5 は、チェックなしとありの両方で試して、メモに分けて書く
- 変換中の行の下に「変換中」と出る

## 記録ファイル

キーの押下と入力欄の変化を、`~/compose-ime-log.txt`（Windows は `C:\Users\<名前>\compose-ime-log.txt`）に追記する。点検の前に消しておき、点検のあとに点検表と一緒に送る。
