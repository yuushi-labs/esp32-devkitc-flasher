# esp32-devkitc-flasher

ESP32-DevKitC 向けの Android 専用 USB OTG ファームウェア書き込み＆シリアルモニターアプリです（Kotlin + Jetpack Compose + Material 3）。v0.2.0 から **LittleFS の生成・書き込み・読み出し（データ吸い出し）** に対応しました。

ファームウェアリポジトリ [`yuushi-labs/esp32-devkitc-firmware`](https://github.com/yuushi-labs/esp32-devkitc-firmware) などの GitHub Releases から直接 `.bin` を取得して書き込めるほか、端末内のローカル `.bin` ファイル書き込みとシリアルモニターにも対応します。

## 主な機能
- **USB OTG シリアル接続**: CP2102 / CH340 / FT232 / USB-CDC など主要な ESP32-DevKitC 搭載チップを自動認識
- **ESP32 ROM ブートローダー書き込み**: DTR/RTS 自動ブート突入＋SLIP プロトコルでの `.bin` 焼き込み（進捗 %・KB/s・転送バイト数・ログ表示）
  - `SYNC` → `READ_REG`（チップ種別・MAC）→ `SPI_ATTACH` → `FLASH_DEFL_BEGIN/DATA/END`（zlib 圧縮）
  - SYNC・チップ情報取得は常に `115200` bps。成功後に `CHANGE_BAUDRATE` で設定値（既定 `460800`）へ上げ、失敗した場合は `115200` のまま書き込む
  - 圧縮が拒否された場合は自動で非圧縮 `FLASH_BEGIN/DATA/END` にフォールバック
  - 4KB セクタ整合・ブロックチェックサム・シーケンス番号を実装し、完了後にハードリセット
- **GitHub Releases 連携書き込み**: 登録したリポジトリ（既定: `yuushi-labs/esp32-devkitc-firmware`）の Release 一覧から `.bin` / `flash-manifest.json` を取得
  - `merged`（`0x0000`）と `parts`（`0x1000` / `0x8000` / `0x10000` など）を自動判別して選択
  - SHA-256（アセット digest / manifest）検証、ETag によるキャッシュ再検証
- **Pin Wiring Guide**: `flash-manifest.json` の `pins`（`gpio` / `label` / `direction` / `activeLevel` / `note`）、`wiringNotes`、`powerNote` をカード表示
  - **ESP32-DevKitC ボード図（Compose Canvas）**: トップビューを 30ピン / 38ピン切替で描画し、`pins` の GPIO と参照された GND / 電源ピンへ色付きリング＋ラベルを表示。ピンをタップすると役割・配線メモ・注意点を表示
  - GPIO6〜11（内蔵フラッシュ専用）、GPIO34〜39（入力専用）、3.3V ロジックのリファレンス付き
- **ローカル `.bin` 書き込み**: SAF で端末内の `.bin` を選び、アドレス指定（`0x0000` 結合済み / `0x1000` ブートローダー / `0x8000` パーティションテーブル / `0xE000` OTADATA / `0x10000` アプリ / `0x290000` LittleFS (4MB default) / `0x3D0000` LittleFS (min_spiffs) / 任意オフセット）で書き込み
- **LittleFS 対応（v0.2.0）**: 追加タブ「LittleFS」から 3 つのことができます
  - **スマホのフォルダ・ファイルから LittleFS v2 イメージを生成して書き込み**: `OpenDocumentTree`（フォルダ）/ `OpenDocument`（複数ファイル）で登録し、相対パス・サイズ・使用率バーを表示。パーティション容量を超える場合は書き込み前にエラー。生成した `.bin` は端末へ保存も可能
  - **ビルド済み `littlefs.bin` / `spiffs.bin` の書き込み**: `flash-manifest.json` の `filesystems`（`type` / `offset` / `size` / `asset` / `sha256`）を解析し、ファームウェアと一緒に、または単体で正しいオフセットへ書き込み
  - **ESP32 からの読み出し・データ吸い出し**: パーティションテーブル（`0x8000`）を読んで MD5 を検証し、`littlefs` / `spiffs` パーティションを自動検出します。一覧表示・テキストプレビュー・1 ファイル保存・ZIP 一括保存は **LittleFS イメージのみ** が対象です（SPIFFS はパーティションとして検出・`.bin` 保存まではできますが、中身の解析には対応していません）
  - 生成も解析も **純 Kotlin 実装**（NDK 不要）。`littlefs-python` との相互運用テストで、生成したイメージが実物の littlefs でマウントできること、逆に littlefs が作ったイメージを 1 バイトも違わず読めることを検証しています
- **シリアルモニター**: ボーレート切替、タイムスタンプ、送受信ログ、コマンド送信（LF/CRLF）、EN リセット
  - 狭い画面でも潰れないコンパクトなヘッダー、横スクロールの改行モード／クイックコマンドバー（`HELP` `PING` `INFO` `PINOUT` `LED ON/OFF/BLINK`）、ログ全文コピー、送信履歴チップ、IME に追従する入力欄
- **アプリ内自動アップデート**: 自リポジトリの Release を ETag 付きで確認し、APK をダウンロードして `FileProvider` 経由でインストーラを起動

詳細は [SPEC.md](./SPEC.md) を参照してください。

## 変更履歴

### v0.2.0 (versionCode 3)
- **LittleFS 生成**: スマホのフォルダ・ファイルから littlefs v2.1 イメージを純 Kotlin で生成（superblock / metadata pair / CTZ skip-list / CRC-32）。ESP32 標準の `block_size = 4096`、`block_count = パーティションサイズ / 4096`
- **LittleFS パーサー**: 読み出したイメージのファイル・ディレクトリ一覧、サイズ、内容の取り出しに対応（複数コミットのログ再生、create/delete スプライス、サブディレクトリ、CTZ 大ファイル、日本語ファイル名）
- **ESP32 からのフラッシュ読み出し**: ROM には `READ_FLASH (0xD2)` がないため、Espressif のスタブフラッシャーを `MEM_BEGIN` / `MEM_DATA` / `MEM_END` で RAM に書き込んで `READ_FLASH` を使用（MD5 検証付き・64 KB 単位）。スタブが起動できない場合は ROM の `READ_FLASH_SLOW (0x0E)` に自動フォールバック
- **パーティションテーブル解析**: `0x8000` を読んでエントリと MD5 を解析し、`littlefs` / `spiffs` / `fat` を自動検出。`default.csv`（`0x290000` / `0x160000` = 1,441,792 B）、`min_spiffs.csv`（`0x3D0000` / `0x20000`）、カスタム入力のプリセット付き
- **LittleFS タブ**: フォルダ・複数ファイル登録、使用率バー、生成して書き込み、`.bin` 保存、ESP32 から読み取り → 一覧 → プレビュー → 単体 / ZIP / イメージ保存
- `flash-manifest.json` の `filesystems` とローカル書き込みアドレスのプリセット（ブートローダー / パーティションテーブル / OTADATA / LittleFS）を追加

### v0.1.1 (versionCode 2)
- 書き込みボーレートの同期フローを修正。SYNC・チップ情報取得を常に `115200` bps で行い、成功後に `CHANGE_BAUDRATE` で設定値（既定 `460800`）へ上げる。変更できないブリッジでは `115200` のまま書き込む
- USB ポートを `115200` bps でオープンし、書き込み後にシリアルモニターのボーレートへ戻す
- シリアルモニター画面を刷新（360dp 幅でも上部が潰れないヘッダー、横スクロールの操作バー、ログ全文コピー、送信履歴、IME 追従）

### v0.1.0 (versionCode 1)
- 初回リリース

## 構成

```
android/
├── app/src/main/java/com/yuushilabs/esp32flasher/
│   ├── core/        # 純 Kotlin（JVM テスト対象）: SLIP / ESP プロトコル / manifest / アドレス / 更新判定 / ピン配列
│   │                 #   LittleFS 生成・解析（LittleFsImageBuilder / LittleFsReader）、パーティションテーブル解析
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

LittleFS の相互運用テストは `littlefs-python` があると Gradle のテスト実行中に自動で走ります（無い場合はスキップ）。手元で同じ検証だけを流す場合は次の順序で実行してください。

```bash
pip install littlefs-python
cd android
./gradlew testDebugUnitTest          # 参照イメージを app/build/littlefs-interop/ に書き出し、双方向の検証も行う

# 生成したイメージ（LittleFsImageBuilder）を littlefs-python でマウントして照合
python3 ../tools/littlefs_interop.py verify app/build/littlefs-interop/verify
# littlefs-python 側でイメージを作るだけ（Kotlin 側の読み取り検証は Gradle テストが行う）
python3 ../tools/littlefs_interop.py fixtures app/build/littlefs-interop/fixtures
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
