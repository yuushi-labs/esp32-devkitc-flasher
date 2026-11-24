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
│   │   ├── HexAddressParser.kt       # アドレス検証とプリセット（結合済み/ブートローダー/パーティション/OTADATA/アプリ/LittleFS）
│   │   ├── UpdateDecision.kt         # アプリ自己アップデート判定（ETag/versionCode/semver）
│   │   ├── PartitionTableParser.kt   # 0x8000 の ESP32 パーティションテーブル（32 バイトエントリ + MD5）解析
│   │   ├── LittleFsFormat.kt         # littlefs v2.1 のタグ・CRC-32・CTZ 幾何・LE ヘルパー
│   │   ├── LittleFsImageBuilder.kt   # 純 Kotlin の littlefs v2.1 イメージ生成
│   │   ├── LittleFsReader.kt         # 純 Kotlin の littlefs v2.1 読み取り（一覧・内容・CTZ）
│   │   ├── LittleFsPartitionPreset.kt# default.csv / min_spiffs.csv / カスタム
│   │   └── EspStub.kt                # Espressif スタブフラッシャーの JSON 解析
│   ├── usb/
│   │   ├── UsbSerialTransport.kt     # usb-serial-for-android ラッパー、DTR/RTS ブートシーケンス、ボーレート変更
│   │   └── EspFlasherEngine.kt       # SYNC → SPI_ATTACH → FLASH_BEGIN/DATA/END（圧縮/非圧縮）→ リセット
│   │                                 #   スタブ書き込み（MEM_BEGIN/DATA/END）と READ_FLASH / READ_FLASH_SLOW も担当
│   ├── github/
│   │   └── FirmwareReleaseClient.kt  # GitHub Releases API 取得・キャッシュ・.bin ダウンロード（SHA-256 検証）
│   ├── data/
│   │   └── AppSettingsRepository.kt  # 対象リポ設定・ボーレート・書き込み設定保存（DataStore）
│   ├── update/
│   │   └── AppUpdateManager.kt       # 本アプリ自身の GitHub Releases 自動アップデート
│   └── ui/
│       ├── FlasherScreen.kt          # USB接続状態・GitHub Release取得・ローカル.bin選択・書き込み進捗
│       ├── LittleFsScreen.kt         # パーティション・ファイル登録・生成/書き込み・ESP32 読み出し・プレビュー
│       ├── LittleFsViewModel.kt      # LittleFS モードの状態管理（SAF、生成、読み出し、保存）
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
   - 同期は常に `115200` bps（`EspFlasherEngine.INITIAL_SYNC_BAUD`）で行う。USB ポートを高速ボーレートで開いていても、`SYNC` 送信前に必ず `115200` へ戻す（再同期・圧縮フォールバック後の再 SYNC も同様）。
   - `SYNC`（`0x08`）を複数回送信して同期確立。
   - `READ_REG` 等でチップ種別・MAC アドレスを読み取り UI に表示（チップ情報の取得も `115200` で行う）。
   - チップ情報取得後に `CHANGE_BAUDRATE`（`0x0F`）で `FlashConfig.targetBaudRate`（既定 `460800`）へ切り替える。切り替えに失敗した場合は `115200` のまま安全に書き込みを続行する。
   - USB ポートは `115200` でオープンし、書き込み終了後は `endExclusiveAccess` でシリアルモニターのボーレートへ戻す。
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

## 5.5 LittleFS 対応（v0.2.0）

この環境には Android NDK がないため、littlefs の C 実装をビルドせずに **同じディスクフォーマットを純 Kotlin で読み書きする**。

### 5.5.1 イメージ生成（`LittleFsImageBuilder`）
- 対象は littlefs **v2.1**（`LFS_DISK_VERSION = 0x00020001`、esp_littlefs / Arduino-ESP32 / PlatformIO と同じ）。
- スーパーブロック: `"littlefs"` マジック + `version / block_size / block_count / name_max / file_max / attr_max`（24 バイト）。`block_size = 4096`、`block_count = パーティションサイズ / 4096`、`name_max = 32`（mklittlefs と同じ控えめな値。esp_littlefs の `LFS_NAME_MAX=255` でも 32 は受け入れられる）、`file_max = 0x7FFFFFFF`、`attr_max = 1022`。
- レイアウト: ブロック 0/1 がルートの metadata pair（スーパーブロック + ルートのエントリ）。以降はサブディレクトリごとに 2 ブロックの pair、最後に大きなファイルの CTZ データブロック。
- metadata ブロックは littlefs のコンパクションと同じ 1 コミット構成: リビジョン（LE 32bit）→ 各エントリのタグ（big-endian で前タグと XOR）→ ブロック末尾まで埋める CRC タグ（データ部 1022 バイトずつに分割）。
- エントリは `NAME`（型 1=REG / 2=DIR）+ 続く `INLINESTRUCT`（256 バイト以下）/ `CTZSTRUCT`（head + size）/ `DIRSTRUCT`（pair）。名前は UTF-8 バイト順に並べる（littlefs と同じ比較）。
- 1 ブロックに収まらないディレクトリは `HARDTAIL (0x601)` で複数の pair に分割し、2 つ目以降の pair の id は 0 から振り直す（littlefs の `lfs_dir_split` と同じ）。
- 大きいファイルは CTZ skip-list（`block_size - 8` ストライド、`ctz(index)+1` 本のポインタ）で書き、リンクを末尾から先頭へたどるため **末尾のデータブロックを head** として `CTZSTRUCT`（head + size）に格納する。
- CRC は littlefs の `lfs_crc`（多項式 `0x04c11db7` のリフレクテッド 16 エントリ表、初期値 `0xffffffff`）。

### 5.5.2 読み取り（`LittleFsReader`）
- metadata pair は 2 ブロックのうち**有効なコミットを持つ新しいリビジョン**を採用し、CRC タグで区切られたコミットを順に再生する（後続コミットが上書き、`CREATE`/`DELETE` スプライスで id を詰め替え）。
- ディレクトリは `HARDTAIL` チェーンをたどって全ブロックを結合し、`SOFTTAIL`（メタデータスレッド）は一覧に含めない。ルートはスーパーブロックを持つ最後の pair。
- ファイルは inline データ、または `CTZSTRUCT` の head/size から `lfs_ctz_find` と同じ手順でブロックをたどって読み出す。
- 日本語ファイル名は UTF-8 バイト列としてそのまま扱う。

### 5.5.3 ESP32 からの読み出し
- **ROM ブートローダーには `READ_FLASH (0xD2)` が実装されていない**（stub 専用）。そこで first に Espressif のスタブフラッシャー（`esp-flasher-stub v1.4.1`、`assets/esp32-stub-flasher.json`、Apache-2.0 / MIT）を `MEM_BEGIN (0x05)` → `MEM_DATA (0x07)` → `MEM_END (0x06)` で RAM に書き込み、`OHAI` を待ってから `READ_FLASH` を使う。
  - `READ_FLASH` は 64 KB 単位。スタブは 4 KB ごとの SLIP フレームで返し、各フレーム後に**累計受信バイト数（4 バイト LE）**を返す必要がある。最後に 16 バイトの MD5 が来るので必ず照合する。
- スタブの起動を確認できない場合は **ROM の `READ_FLASH_SLOW (0x0E)`** にフォールバックする（64 バイト/コマンド、`SPI_SET_PARAMS` でフラッシュサイズを設定）。遅いが ESP32 の ROM 単体でも確実に動く経路。
- まず `0x8000` の 4 KB を読んで `PartitionTableParser` でテーブルを解析（`0xAA50` マジック、32 バイトエントリ、末尾の `0xEBEB` MD5 を検証）。`littlefs (0x83)` / `spiffs (0x82)` / `fat (0x81)` を検出し、`littlefs` を優先して読み出す。読み取り不可・カスタム時はプリセット／手入力にフォールバック。
- 読み出したイメージは `LittleFsReader` で一覧化し、テキストプレビュー・1 ファイル保存・ZIP 一括保存・`.bin` 保存に対応。

### 5.5.4 検証
- `littlefs-python`（参照実装）との相互運用テスト（`LittleFsInteropScriptTest` と `tools/littlefs_interop.py`）:
  - `LittleFsImageBuilder` が生成したイメージ（空 / 単一 / ネスト / 日本語 / CTZ 大ファイル / ルート分割する 150 ファイル）を littlefs-python がマウントし、内容が 1 バイトも違わないこと
  - littlefs-python が生成したイメージを `LittleFsReader` が 1 バイトも違わず読めること
- 参照実装が作ったイメージを `app/src/test/resources/littlefs/` に固定し、Python が無い環境でも `LittleFsFixtureTest` が常に検証する。
- プロトコル層は `EspFlasherEngineReadTest` が esptool と同じ応答モデル（64 バイトの `READ_FLASH_SLOW`、4 KB フレーム + 累計 ACK + MD5 の `READ_FLASH`）で検証する。**実機での確認はユーザーの ESP32-DevKitC で行う必要がある**。

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
- LittleFS の相互運用テスト（littlefs-python 双方向）全通過
- タグ `v0.2.0` 作成＋GitHub Release 公開（`esp32-devkitc-flasher-v0.2.0.apk` 添付、`versionCode: 3`）
