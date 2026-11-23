# esp32-devkitc-flasher 設計仕様書 (SPEC)

## 1. 目的
PC を使わずに Android スマートフォン（OPPO / ColorOS を含む一般 Android 端末）から USB OTG 接続で ESP32-DevKitC にファームウェア（`.bin`）を書き込み、そのままシリアルモニターで動作確認できる専用アプリを提供する。

## 2. ターゲット環境
- **Android**: Kotlin / Jetpack Compose / Material 3、`minSdk = 26`、`targetSdk = 35`
- **対象デバイス**: ESP32-DevKitC（USB-UART: Silicon Labs CP210x、WCH CH340/CH341、FTDI FT232、Espressif USB-JTAG/Serial CDC 等）
- **接続方式**: USB OTG（Android USB Host API + `usb-serial-for-android`）

## 3. アーキテクチャとモジュール分離
純粋 Kotlin で JVM 単体テスト可能なコア層と、Android USB / UI 層を明確に分離する。

```
android/
├── app/src/main/java/com/yuushilabs/esp32flasher/
│   ├── core/
│   │   ├── SlipCodec.kt              # SLIP フレームエンコード/デコード
│   │   ├── EspBootloaderProtocol.kt  # ESP32 ROM ブートローダーコマンド構築・応答解析・zlib圧縮ブロック分割
│   │   ├── FlashManifestParser.kt    # flash-manifest.json と GitHub Release アセット解析
│   │   ├── HexAddressParser.kt       # 0x0000 / 0x10000 等のアドレス検証
│   │   └── UpdateDecision.kt         # アプリ自己アップデート判定（ETag/versionCode/semver）
│   ├── usb/
│   │   ├── UsbSerialTransport.kt     # usb-serial-for-android ラッパー、DTR/RTS ブートシーケンス、ボーレート変更
│   │   └── EspFlasherEngine.kt       # SYNC → SPI_ATTACH → FLASH_BEGIN/DATA/END（圧縮/非圧縮）→ リセット
│   ├── github/
│   │   └── FirmwareReleaseClient.kt  # GitHub Releases API 取得・キャッシュ・.bin ダウンロード（SHA-256 検証）
│   ├── data/
│   │   └── AppSettingsRepository.kt  # 対象リポ設定・ボーレート・書き込み設定保存（DataStore）
│   ├── update/
│   │   └── AppUpdateManager.kt       # 本アプリ自身の GitHub Releases 自動アップデート
│   └── ui/
│       ├── FlasherScreen.kt          # USB接続状態・GitHub Release取得・ローカル.bin選択・書き込み進捗
│       ├── SerialMonitorScreen.kt    # シリアルログ・送信・ボーレート・リセットボタン
│       └── SettingsScreen.kt         # 連携リポジトリ設定・OPPO OTG ガイド・アプリ更新情報
└── keystore/
    └── esp32-flasher.jks             # 固定配布署名鍵（上書き更新互換のためリポジトリ同梱）
```

## 4. ESP32 書き込みプロトコル仕様（ROM ブートローダー）
1. **自動ブートローダー突入（DTR/RTS）**:
   - `esptool.py` 互換のシーケンス（`DTR=false, RTS=true` → `DTR=true, RTS=false` → 短時間待機 → `DTR=false, RTS=false`）を実行する。
   - 自動リセットが効かないボード向けに「手動 BOOT ボタンを押しながらリセット」の案内とリトライボタンも用意する。
2. **SYNC とチップ確認**:
   - `SYNC`（`0x08`）を複数回送信して同期確立。
   - `READ_REG` 等でチップ種別・MAC アドレスを読み取り UI に表示。
3. **フラッシュ書き込み**:
   - `SPI_ATTACH`（`0x0D`）で内蔵 SPI フラッシュを有効化。
   - 高速化のため `FLASH_DEFL_BEGIN`（`0x10`）/ `FLASH_DEFL_DATA`（`0x11`）/ `FLASH_DEFL_END`（`0x12`）による zlib 圧縮書き込みを基本とし、必要に応じ非圧縮（`FLASH_BEGIN` `0x02` / `FLASH_DATA` `0x03` / `FLASH_END` `0x04`）もサポート。
   - チェックサム計算・シーケンス番号・フラッシュ消去サイズ（ブロック数・4KB セクタ整合）を正確に実装し、JVM テストでモックトランスポート相手に全シーケンスを検証する。
   - 書き込み中の進捗（%・KB/s・転送済みバイト数）をリアルタイム表示。
   - 完了後はハードリセット（RTS トグル）を実行し、ワンタップでシリアルモニター画面へ遷移可能にする。

## 5. GitHub Releases 連携＆ローカル `.bin` 仕様
- **既定リポジトリ**: `yuushi-labs/esp32-devkitc-firmware`（設定画面で任意の `owner/repo` に変更・複数履歴保持可能）
- **Release 取得**:
  - `GET https://api.github.com/repos/{owner}/{repo}/releases`（および `releases/latest`）で公開 Release 一覧を取得。
  - Release 内の `flash-manifest.json` があればそれを優先解析して `merged`（`0x0000`）または `parts`（`0x10000` 等）を自動選択・SHA-256 検証。無い場合は `.bin` アセットを一覧表示してユーザーが選択・書き込みアドレスを指定できる。
  - `flash-manifest.json` に `pins` 配列（各要素: `gpio`, `label`, `direction`, `activeLevel`, `note` 等、任意）が含まれる場合、Release 選択時・書き込み前後・専用「ピン配線ガイド」シート／画面で **「どの GPIO ピンに何を繋ぐか」** をカード一覧でわかりやすく表示する。さらに **ESP32-DevKitC のボード図（トップビュー、30ピン / 38ピン切替対応）を Canvas でリアルに描画し、`pins` に含まれる GPIO ピンと対応する GND ピンに色付きの丸（ハイライト＋引き出しラベル）を正確なピン位置へ表示し、ピンをタップすると役割と配線注意点を確認できる** ようにする。あわせて ESP32-DevKitC 標準の注意ピン（GPIO6〜11 は内蔵フラッシュ専用で使用不可、GPIO34〜39 は入力専用、3.3V / GND / VIN 電源注意）のリファレンスも参照できるようにする。
- **ローカル `.bin` 選択**:
  - SAF（`ActivityResultContracts.OpenDocument`）で `.bin` を読み込み、書き込み先アドレス（プリセット `0x0000 結合済み` / `0x10000 アプリ単体` / 任意入力）を選んで書き込み。

## 6. 権限と OPPO（ColorOS）配慮
- 不要な権限は要求しない（`INTERNET`, `REQUEST_INSTALL_PACKAGES` のみ。USB Host は `uses-feature android.hardware.usb.host` `required="false"` と `USB_DEVICE_ATTACHED` / 実行時 `UsbManager.requestPermission` で対応）。
- OPPO / ColorOS 端末では設定の「OTG 接続」がオフだと USB デバイスが見えないことがあるため、未検出時の案内カードに設定手順を表示する。

## 7. アプリ内自動アップデートと固定署名鍵
- `https://api.github.com/repos/yuushi-labs/esp32-devkitc-flasher/releases/latest` を ETag（304）付きで確認し、新しい `versionCode` が公開されていればバナー通知 → 進捗付きダウンロード → `FileProvider` 経由でインストーラを起動する。
- `android/keystore/esp32-flasher.jks` に固定配布署名鍵を保持し、Release ビルドは常にこの鍵で署名して上書きインストール互換性を維持する。

## 8. 検証基準
- JVM ユニットテスト（SLIP、ESP ブートローダーコマンド構築・応答・圧縮分割、Manifest 解析、Hex アドレス、UpdateDecision）全通過
- `./gradlew lintDebug` エラー 0
- `./gradlew assembleRelease` 成功 + `apksigner verify` 通過
- タグ `v0.1.0` 作成＋GitHub Release 公開（`esp32-devkitc-flasher-v0.1.0.apk` 添付）
