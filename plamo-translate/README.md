# PLaMo翻訳 (Android)

PLaMo 3 Translate 0.8B (Q4_0 GGUF) を llama.cpp で端末内実行する翻訳アプリ。
初回起動時にモデル(約548MB)を Hugging Face から自動ダウンロードします。

## APKの作り方(Android Studio不要)
1. GitHubで新しいリポジトリを作り、このフォルダの中身をすべてpush(`.github`フォルダ含む)
2. Actionsタブ → "Build APK" が自動で走る(約10〜20分)
3. 完了後、Artifacts の `PlamoTranslate-apk` をダウンロード → zipを展開 → `app-release.apk` をインストール

## ローカルビルド
Android Studio (JDK17, NDK, CMake 3.22.1) で開いて Build、または `gradle assembleRelease`

## 注意
- llama.cpp は master を取得します。将来のAPI変更でビルドが壊れたら `CMakeLists.txt` の `LLAMA_TAG` を固定してください
- 対応端末: arm64-v8a / Android 8.0以上
- モデルのライセンス: PLaMo Community License(個人・非商用は無料)
