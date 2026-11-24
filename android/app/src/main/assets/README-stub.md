# esp32-stub-flasher.json

A verbatim copy of `stub_flasher/2/esp32.json` from `esptool`
(https://github.com/espressif/esptool), which in turn ships the binaries released at
https://github.com/espressif/esp-flasher-stub/releases/tag/v1.4.1.

The binaries are dual licensed under the Apache License Version 2.0 or the MIT license
(see the upstream `LICENSE-APACHE` / `LICENSE-MIT` files for the full texts).

The app copies these two segments into the ESP32's RAM with `MEM_BEGIN` / `MEM_DATA` /
`MEM_END` so it can use `READ_FLASH (0xD2)`, which only the stub implements, when it reads
back a LittleFS partition. Licence texts: https://github.com/espressif/esp-flasher-stub
