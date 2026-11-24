#!/usr/bin/env python3
"""littlefs interoperability checks between the app's Kotlin implementation and littlefs.

Two directions are covered:

  verify <dir>     images written by LittleFsImageBuilder (plus <dir>/manifest.tsv) are mounted
                   with littlefs-python (the reference implementation) and compared byte for byte
  fixtures <dir>   littlefs-python writes its own images into <dir>, together with <dir>/manifest.tsv,
                   so the Kotlin LittleFsReader can read a foreign image

Run with:  python3 tools/littlefs_interop.py verify build/littlefs-interop
Requires `pip install littlefs-python`.
"""

import hashlib
import os
import sys

from littlefs import LittleFS, UserContext

BLOCK_SIZE = 4096


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_manifest(path):
    """manifest.tsv: 'scenario\\timage' lines followed by '  <path>\\t<sha256>' lines."""
    scenarios = []
    current = None
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle.read().splitlines():
            if not line.strip():
                continue
            if not line.startswith("  "):
                name, _, image = line.partition("\t")
                current = {"name": name, "image": image, "files": {}}
                scenarios.append(current)
            elif current is not None:
                file_path, _, digest = line.strip().partition("\t")
                current["files"][file_path] = digest
    return scenarios


def mount(image_path, block_count):
    with open(image_path, "rb") as handle:
        data = bytearray(handle.read())
    fs = LittleFS(
        UserContext(buffer=data),
        mount=False,
        block_size=BLOCK_SIZE,
        block_count=block_count,
    )
    fs.mount()
    return fs, len(data)


def walk(fs, prefix=""):
    out = []
    for name in fs.listdir(prefix or "/"):
        if name in (".", ".."):
            continue
        path = f"{prefix}/{name}".lstrip("/")
        stat = fs.stat(path)
        if stat.type == 2:
            out.append((path, None))
            out.extend(walk(fs, path))
        else:
            out.append((path, stat.size))
    return out


def verify(directory):
    manifest_path = os.path.join(directory, "manifest.tsv")
    scenarios = read_manifest(manifest_path)
    if not scenarios:
        print(f"no scenarios in {manifest_path}")
        return 1
    failures = 0
    for scenario in scenarios:
        name = scenario["name"]
        image = scenario["image"]
        size = os.path.getsize(image)
        block_count = size // BLOCK_SIZE
        try:
            fs, _ = mount(image, block_count)
        except Exception as error:  # noqa: BLE001 - report every mount failure to the caller
            print(f"[FAIL] {name}: littlefs-python could not mount the image: {error}")
            failures += 1
            continue
        actual = {}
        for path, file_size in walk(fs):
            if file_size is None:
                continue
            with fs.open("/" + path, "rb") as handle:
                actual[path] = (file_size, sha256(handle.read()))
        expected = scenario["files"]
        problems = []
        if set(actual) != set(expected):
            problems.append(f"paths differ: extra={sorted(set(actual) - set(expected))} "
                            f"missing={sorted(set(expected) - set(actual))}")
        for path in sorted(set(actual) & set(expected)):
            if actual[path][1] != expected[path]:
                problems.append(f"{path}: sha256 {actual[path][1]} != {expected[path]}")
        if problems:
            failures += 1
            print(f"[FAIL] {name}: " + "; ".join(problems))
        else:
            print(f"[ OK ] {name}: {len(actual)} files mounted and byte-identical")
    if failures:
        print(f"{failures} scenario(s) failed")
        return 1
    return 0


def make_fixtures(directory):
    """littlefs-python writes the images; the Kotlin reader checks it can read them back."""
    os.makedirs(directory, exist_ok=True)
    manifest = []
    fixtures = {
        "py_empty": [],
        "py_single": [("hello.txt", b"hello from littlefs-python\n")],
        "py_multi": [
            ("a.txt", b"A" * 100),
            ("b.bin", bytes(range(8))),
            ("zero.txt", b""),
        ],
        "py_nested": [
            ("docs/memo.txt", b"memo" * 100),
            ("docs/sub/deep.txt", b"deep"),
            ("docs/sub/data.bin", bytes((index % 251) for index in range(300))),
            ("top.txt", b"top"),
        ],
        "py_japanese": [
            ("日本語.txt", "こんにちは、世界！".encode()),
            ("フォルダ/メモ.txt", "メモです".encode()),
            ("フォルダ/画像データ.bin", bytes((index % 256) for index in range(1000))),
        ],
        "py_big": [("big.bin", bytes((index * 7) % 256 for index in range(20000)))],
    }
    # The churn image goes through create / delete / grow, so its metadata log has several
    # commits with splice tags and an inline file that became a CTZ file.
    churn = LittleFS(block_size=BLOCK_SIZE, block_count=32)
    with churn.open("/a.txt", "w") as handle:
        handle.write("a" * 10)
    with churn.open("/b.txt", "w") as handle:
        handle.write("b" * 20)
    with churn.open("/c.txt", "w") as handle:
        handle.write("c" * 30)
    churn.remove("/b.txt")
    with churn.open("/d.bin", "wb") as handle:
        handle.write(bytes(5000))
    with churn.open("/a.txt", "a") as handle:
        handle.write("x" * 3000)
    churn_image = bytes(churn.context.buffer)
    churn_path = os.path.join(directory, "py_churn.bin")
    with open(churn_path, "wb") as handle:
        handle.write(churn_image)
    manifest.append(f"py_churn\t{os.path.abspath(churn_path)}")
    churn_files = {}
    for name in sorted(churn.listdir("/")):
        with churn.open("/" + name, "rb") as handle:
            churn_files[name] = handle.read()
        manifest.append(f"  {name}\t{sha256(churn_files[name])}")
    print(f"[made] py_churn: {len(churn_files)} files, {len(churn_image)} bytes")

    for name, files in fixtures.items():
        block_count = 32
        fs = LittleFS(block_size=BLOCK_SIZE, block_count=block_count)
        directories = set()
        for path, data in files:
            segments = path.split("/")
            for depth in range(1, len(segments)):
                directories.add("/".join(segments[:depth]))
        for path in sorted(directories):
            fs.mkdir("/" + path)
        for path, data in files:
            with fs.open("/" + path, "wb") as handle:
                handle.write(data)
        image = bytes(fs.context.buffer)
        image_path = os.path.join(directory, f"{name}.bin")
        with open(image_path, "wb") as handle:
            handle.write(image)
        manifest.append(f"{name}\t{os.path.abspath(image_path)}")
        for path, data in sorted(files):
            manifest.append(f"  {path}\t{sha256(data)}")
        print(f"[made] {name}: {len(files)} files, {len(image)} bytes")
    with open(os.path.join(directory, "manifest.tsv"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(manifest) + "\n")
    return 0


def make_test_resources(directory):
    """Writes the committed reference images used by LittleFsFixtureTest (littlefs-python made)."""
    os.makedirs(directory, exist_ok=True)
    manifest = []
    fixtures = {
        "littlefs-python-empty": [],
        "littlefs-python-mixed": [
            ("hello.txt", b"hello littlefs\n"),
            ("docs/memo.txt", b"memo" * 100),
            ("docs/sub/deep.txt", b"deep"),
            ("docs/sub/data.bin", bytes((index % 251) for index in range(300))),
            ("empty.txt", b""),
            ("日本語.txt", "こんにちは、世界！".encode()),
            ("フォルダ/メモ.txt", "メモです".encode()),
        ],
        "littlefs-python-big": [("big.bin", bytes((index * 7) % 256 for index in range(20000)))],
    }
    for name, files in fixtures.items():
        block_count = 16
        fs = LittleFS(block_size=BLOCK_SIZE, block_count=block_count)
        directories = set()
        for path, _ in files:
            segments = path.split("/")
            for depth in range(1, len(segments)):
                directories.add("/".join(segments[:depth]))
        for path in sorted(directories):
            fs.mkdir("/" + path)
        for path, data in files:
            with fs.open("/" + path, "wb") as handle:
                handle.write(data)
        image = bytes(fs.context.buffer)
        with open(os.path.join(directory, f"{name}.bin"), "wb") as handle:
            handle.write(image)
        manifest.append(f"{name}\t{name}.bin")
        for path, data in sorted(files):
            manifest.append(f"  {path}\t{sha256(data)}")
        print(f"[made] {name}: {len(files)} files, {len(image)} bytes")
    with open(os.path.join(directory, "manifest.tsv"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(manifest) + "\n")
    return 0


def append(directory):
    """Mounts <directory>/image.bin read-write, adds a file, then prints every file's sha256.

    This checks block reuse: after the reference allocator has written into an image the app
    produced, every file that was there before must still read back unchanged.
    """
    image_path = os.path.join(directory, "image.bin")
    with open(image_path, "rb") as handle:
        data = bytearray(handle.read())
    block_count = len(data) // BLOCK_SIZE

    def mount():
        fs = LittleFS(
            UserContext(buffer=data),
            mount=False,
            block_size=BLOCK_SIZE,
            block_count=block_count,
        )
        fs.mount()
        return fs

    fs = mount()
    payload = bytes((index * 11) % 256 for index in range(6000))
    with fs.open("/added-by-littlefs.bin", "wb") as handle:
        handle.write(payload)
    fs.unmount()
    with open(image_path, "wb") as handle:
        handle.write(data)

    print(f"added\t{sha256(payload)}")
    fs = mount()
    for path, size in walk(fs):
        if size is None:
            continue
        with fs.open("/" + path, "rb") as handle:
            print(f"{path}\t{size}\t{sha256(handle.read())}")
    return 0


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    command = argv[0]
    directory = argv[1]
    if command == "verify":
        return verify(directory)
    if command == "fixtures":
        return make_fixtures(directory)
    if command == "testresources":
        return make_test_resources(directory)
    if command == "append":
        return append(directory)
    print(f"unknown command: {command}")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
