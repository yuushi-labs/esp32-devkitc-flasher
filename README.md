# esp32-devkitc-flasher

ESP32-DevKitC 向けの Android 専用 USB OTG ファームウェア書き込み＆シリアルモニターアプリです（Kotlin + Jetpack Compose + Material 3）。

ファームウェアリポジトリ [`yuushi-labs/esp32-devkitc-firmware`](https://github.com/yuushi-labs/esp32-devkitc-firmware) などの GitHub Releases から直接 `.bin` を取得して書き込めるほか、端末内のローカル `.bin` ファイル書き込みとシリアルモニターにも対応します。

## 主な機能
- **USB OTG シリアル接続**: CP2102 / CH340 / FT232 / USB-CDC など主要な ESP32-DevKitC 搭載チップを自動認識
- **ESP32 ROM ブートローダー書き込み**: DTR/RTS 自動ブート突入＋SLIP プロトコルでの `.bin` 焼き込み（進捗バー・速度・ログ表示）
- **GitHub Releases 連携書き込み**: 登録したリポジトリ（既定: `yuushi-labs/esp32-devkitc-firmware`）の最新 Release や過去の Release から `.bin` / `flash-manifest.json` を直接取得して書き込み
- **ローカル `.bin` 書き込み**: 端末内の `.bin` を選んでアドレス指定（`0x0000` 結合済み / `0x10000` アプリ / 任意オフセット）で書き込み
- **シリアルモニター**: ボーレート切替（115200 既定）、送受信ログ、コマンド送信（LF/CRLF）、DTR/RTS リセット
- **アプリ内自動アップデート**: GitHub Releases 連携の更新確認・APK ダウンロード・固定署名鍵による上書き更新

詳細は [SPEC.md](./SPEC.md) を参照してください。
