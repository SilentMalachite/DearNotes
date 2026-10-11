# サードパーティのライセンス

DearNotes 本体は Apache License 2.0 です（[LICENSE](LICENSE)）。インストーラー（.dmg / .msi）には、次のソフトウェアも入っています。それぞれのライセンスに従います。

| ソフトウェア | 版 | ライセンス | 入手先 |
|---|---|---|---|
| OpenJDK ランタイム（jlink で作った JRE） | 25 | GPL v2 + Classpath Exception | https://adoptium.net/ |
| OpenJFX（javafx-base, javafx-graphics, javafx-controls） | 25.0.4 | GPL v2 + Classpath Exception | https://openjfx.io/ |
| RichTextFX | 0.11.7 | BSD 2-Clause | https://github.com/FXMisc/RichTextFX |
| Flowless | 0.7.4 | BSD 2-Clause | https://github.com/FXMisc/Flowless |
| ReactFX | 2.0-M5 | BSD 2-Clause | https://github.com/TomasMikula/ReactFX |
| UndoFX | 2.1.1 | BSD 2-Clause | https://github.com/FXMisc/UndoFX |
| WellBehavedFX | 0.3.3 | BSD 2-Clause | https://github.com/FXMisc/WellBehavedFX |
| SQLite JDBC Driver（xerial） | 3.53.4.0 | Apache License 2.0 | https://github.com/xerial/sqlite-jdbc |
| SQLite（SQLite JDBC Driver に同梱） | — | パブリックドメイン | https://sqlite.org/copyright.html |

同梱している JRE のライセンス文は、jlink がモジュールごとにアプリの中へ入れています。

- macOS: `DearNotes.app/Contents/runtime/Contents/Home/legal/`
- Windows: インストール先の `runtime\legal\`

テストにだけ使う JUnit（Eclipse Public License 2.0）はインストーラーに入りません。

版を上げたときや依存を足したときは、この表も直してください。依存の一覧は次で確かめられます。

```sh
./gradlew dependencies --configuration runtimeClasspath
```
