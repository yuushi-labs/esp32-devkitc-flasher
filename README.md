# esp32-devkitc-flasher

ESP32-DevKitC 向けの Android 専用 USB OTG ファームウェア書き込み＆シリアルモニターアプリです（Kotlin + Jetpack Compose + Material 3）。

ファームウェアリポジトリ [`yuushi-labs/esp32-devkitc-firmware`](https://github.com/yuushi-labs/esp32-devkitc-firmware) などの GitHub Releases から直接 `.bin` を取得して書き込めるほか、端末内のローカル `.bin` ファイル書き込みとシリアルモニターにも対応します。

## 主な機能
- **USB OTG シリアル接続**: CP2102 / CH340 / FT232 / USB-CDC など主要な ESP32-DevKitC 搭載チップを自動認識
- **ESP32 ROM ブートローダー書き込み**: DTR/RTS 自動ブート突入＋SLIP プロトコルでの `.bin` 焼き込み（進捗 %・KB/s・転送バイト数・ログ表示）
  - `SYNC` → `READ_REG`（チップ種別・MAC）→ `SPI_ATTACH` → `FLASH_DEFL_BEGIN/DATA/END`（zlib 圧縮）
  - 圧縮が拒否された場合は自動で非圧縮 `FLASH_BEGIN/DATA/END` にフォールバック
  - 4KB セクタ整合・ブロックチェックサム・シーケンス番号を実装し、完了後にハードリセット
- **GitHub Releases 連携書き込み**: 登録したリポジトリ（既定: `yuushi-labs/esp32-devkitc-firmware`）の Release 一覧から `.bin` / `flash-manifest.json` を取得
  - `merged`（`0x0000`）と `parts`（`0x1000` / `0x8000` / `0x10000` など）を自動判別して選択
  - SHA-256（アセット digest / manifest）検証、ETag によるキャッシュ再検証
- **Pin Wiring Guide**: `flash-manifest.json` の `pins`（`gpio` / `label` / `direction` / `activeLevel` / `note`）、`wiringNotes`、`powerNote` をカード表示
  - **ESP32-DevKitC ボード図（Compose Canvas）**: トップビューを 30ピン / 38ピン切替で描画し、`pins` の GPIO と参照された GND / 電源ピンへ色付きリング＋ラベルを表示。ピンをタップすると役割・配線メモ・注意点を表示
  - GPIO6〜11（内蔵フラッシュ専用）、GPIO34〜39（入力専用）、3.3V ロジックのリファレンス付き
- **ローカル `.bin` 書き込み**: SAF で端末内の `.bin` を選び、アドレス指定（`0x0000` 結合済み / `0x10000` アプリ / 任意オフセット）で書き込み
- **シリアルモニター**: ボーレート切替、タイムスタンプ、送受信ログ、コマンド送信（LF/CRLF）、EN リセット
- **アプリ内自動アップデート**: 自リポジトリの Release を ETag 付きで確認し、APK をダウンロードして `FileProvider` 経由でインストーラを起動

詳細は [SPEC.md](./SPEC.md) を参照してください。

## 構成

```
android/
├── app/src/main/java/com/yuushilabs/esp32flasher/
│   ├── core/        # 純 Kotlin（JVM テスト対象）: SLIP / ESP プロトコル / manifest / アドレス / 更新判定 / ピン配列
│   ├── usb/         # usb-serial-for-android ラッパーと書き込みエンジン
│   ├── github/      # GitHub Releases API クライアント
│   ├── data/        # DataStore 設定
│   ├── update/      # アプリ自己アップデート
│   └── ui/          # Compose 画面・ViewModel・テーマ
├── app/src/test/    # JVM ユニットテスト
└── keystore/        # 固定配布署名鍵（上書き更新互換のためリポジトリ同梱）
```

`core` 層は Android API に依存しないため、USB 実機なしで全プロトコルを JVM テストできます。

## ビルドと検証

JDK 17+ と Android SDK（platform 35 / build-tools）が必要です。

```bash
cd android
./gradlew testDebugUnitTest   # JVM ユニットテスト
./gradlew lintDebug           # Android Lint
./gradlew assembleRelease     # 署名済み APK (app/build/outputs/apk/release/)
```

`local.properties` に `sdk.dir` を設定してください（`.gitignore` 済み）。

### 署名
Release ビルドは `android/keystore/esp32-flasher.jks`（`CN=yuushi-labs`, alias `esp32-flasher`）で署名します。上書きインストール互換性を保つため鍵はリポジトリに同梱しています。

この鍵は公開前提です（仕様 §7）。第三者が同じ鍵で APK に署名できるため、署名一致は「正規ビルドである」ことの証明にはなりません。アプリ内アップデートは Android のインストーラ確認を経由し、署名だけを理由にインストールを自動化しません。

別鍵でプライベートビルドする場合は、パスと認証情報を組で指定します。

```bash
ESP32_FLASHER_STORE_FILE=/path/to/private.jks \
ESP32_FLASHER_STORE_PASSWORD=... \
ESP32_FLASHER_KEY_ALIAS=... \
ESP32_FLASHER_KEY_PASSWORD=... \
./gradlew assembleRelease
```

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## OPPO / ColorOS での注意
OTG 接続がオフだと USB デバイスが見えません。「設定 > その他の設定 > OTG 接続」を ON にしてください。未検出時はアプリ内にも同じ案内が表示されます。
