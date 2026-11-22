#!/usr/bin/env python3
"""GoldLeaf direct-classpath JDK 25 Leyden AOT launcher (goldleaf-ADR-016 Layer A).

Unpacks Paperclip's versions/ and libraries/ once per bundler SHA-256, constructs the exact
META-INF/versions.list + META-INF/libraries.list classpath order, and manages a content-addressed
JDK 25 -XX:AOTCache archive with same-directory atomic os.replace promotion.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import time
import zipfile
from dataclasses import dataclass

DEFAULT_MAIN_CLASS = "org.bukkit.craftbukkit.Main"
MIN_LEYDEN_JAVA_MAJOR = 25


@dataclass(frozen=True)
class BundlerManifest:
    main_class: str
    version_jars: tuple[str, ...]
    library_jars: tuple[str, ...]


def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_bundler_manifest(jar_path: pathlib.Path) -> BundlerManifest:
    with zipfile.ZipFile(jar_path, "r") as zf:
        main_class = DEFAULT_MAIN_CLASS
        if "META-INF/main-class" in zf.namelist():
            raw_main = zf.read("META-INF/main-class").decode("utf-8").strip()
            if raw_main:
                main_class = raw_main

        def _read_list(entry_name: str) -> tuple[str, ...]:
            if entry_name not in zf.namelist():
                raise ValueError(f"Bundler JAR {jar_path} is missing {entry_name}")
            items: list[str] = []
            for raw_line in zf.read(entry_name).decode("utf-8").splitlines():
                line = raw_line.strip()
                if not line:
                    continue
                parts = line.split("\t")
                if len(parts) < 3:
                    raise ValueError(f"Malformed entry in {entry_name}: {raw_line!r}")
                items.append(parts[2])
            return tuple(items)

        return BundlerManifest(
            main_class=main_class,
            version_jars=_read_list("META-INF/versions.list"),
            library_jars=_read_list("META-INF/libraries.list"),
        )


def parse_java_major(version_output: str) -> int:
    match = re.search(r'version\s+"(\d+)(?:\.(\d+))?', version_output)
    if not match:
        return 0
    major = int(match.group(1))
    if major == 1 and match.group(2):
        return int(match.group(2))
    return major


def detect_java_version(java_bin: str) -> tuple[int, str]:
    proc = subprocess.run(
        [java_bin, "-version"],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"Failed to query Java version via {java_bin!r} (rc={proc.returncode}): {proc.stderr.strip()}"
        )
    combined = (proc.stderr or proc.stdout).strip()
    first_line = combined.splitlines()[0] if combined else ""
    return parse_java_major(combined), first_line


def normalize_jvm_args_for_key(jvm_args: list[str]) -> list[str]:
    normalized: list[str] = []
    for arg in jvm_args:
        if arg.startswith("-XX:AOTCache=") or arg.startswith("-XX:AOTCacheOutput="):
            continue
        normalized.append(arg)
    return normalized


def compute_cache_key(
    bundler_sha256: str,
    java_version_line: str,
    jvm_args: list[str],
    main_class: str,
) -> str:
    payload = json.dumps(
        {
            "bundler_sha256": bundler_sha256,
            "java_version": java_version_line,
            "jvm_args": normalize_jvm_args_for_key(jvm_args),
            "main_class": main_class,
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()[:16]


def build_direct_classpath(workdir: pathlib.Path, manifest: BundlerManifest) -> list[pathlib.Path]:
    jars: list[pathlib.Path] = []
    for rel in manifest.version_jars:
        jars.append(workdir / "versions" / rel)
    for rel in manifest.library_jars:
        jars.append(workdir / "libraries" / rel)
    return jars


def ensure_unpacked(
    java_bin: str,
    jar_path: pathlib.Path,
    bundler_sha256: str,
    workdir: pathlib.Path,
    manifest: BundlerManifest,
) -> list[pathlib.Path]:
    workdir.mkdir(parents=True, exist_ok=True)
    cp_jars = build_direct_classpath(workdir, manifest)
    stamp_path = workdir / ".goldleaf-unpacked.json"
    if stamp_path.is_file():
        try:
            stamp = json.loads(stamp_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            stamp = {}
        if stamp.get("bundler_sha256") == bundler_sha256 and all(p.is_file() for p in cp_jars):
            return cp_jars

    proc = subprocess.run(
        [java_bin, "-Dpaperclip.patchonly=true", "-jar", str(jar_path)],
        cwd=str(workdir),
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"Paperclip patchonly unpack failed for {jar_path} (rc={proc.returncode})"
        )
    missing = [str(p) for p in cp_jars if not p.is_file()]
    if missing:
        raise RuntimeError(
            f"Paperclip unpack completed but {len(missing)} expected JAR(s) are missing, e.g. {missing[0]}"
        )
    tmp_stamp = workdir / f".goldleaf-unpacked-{os.getpid()}.json.tmp"
    tmp_stamp.write_text(
        json.dumps({"bundler_sha256": bundler_sha256, "unpacked_at": time.time()}, indent=2) + "\n",
        encoding="utf-8",
    )
    os.replace(tmp_stamp, stamp_path)
    return cp_jars


def is_valid_aot_cache(aot_path: pathlib.Path, meta_path: pathlib.Path, expected_key: str) -> bool:
    if not aot_path.is_file() or aot_path.stat().st_size <= 0 or not meta_path.is_file():
        return False
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    return meta.get("cache_key") == expected_key


def cleanup_aot_temps(tmp_aot: pathlib.Path) -> None:
    tmp_aot.unlink(missing_ok=True)
    pathlib.Path(f"{tmp_aot}.config").unlink(missing_ok=True)


def promote_aot_atomically(
    tmp_aot: pathlib.Path,
    final_aot: pathlib.Path,
    meta_path: pathlib.Path,
    meta_payload: dict[str, object],
) -> bool:
    if not tmp_aot.is_file() or tmp_aot.stat().st_size <= 0:
        cleanup_aot_temps(tmp_aot)
        return False
    os.replace(tmp_aot, final_aot)
    pathlib.Path(f"{tmp_aot}.config").unlink(missing_ok=True)
    tmp_meta = meta_path.parent / f".{meta_path.name}.{os.getpid()}.tmp"
    tmp_meta.write_text(json.dumps(meta_payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(tmp_meta, meta_path)
    return True


def run_headless_training(cmd: list[str], workdir: pathlib.Path, timeout_sec: int) -> int:
    proc = subprocess.Popen(
        cmd,
        cwd=str(workdir),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    sent_stop = False
    deadline = time.monotonic() + max(30, timeout_sec)
    assert proc.stdout is not None
    assert proc.stdin is not None
    try:
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            if not sent_stop and "Done (" in line and "For help, type" in line:
                sent_stop = True
                proc.stdin.write("stop\n")
                proc.stdin.flush()
            if time.monotonic() > deadline:
                proc.terminate()
                raise TimeoutError(f"AOT training exceeded {timeout_sec}s timeout")
        return proc.wait(timeout=max(10, int(deadline - time.monotonic())))
    except KeyboardInterrupt:
        proc.terminate()
        proc.wait(timeout=15)
        raise


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Launch GoldLeaf via exact Paperclip-order direct classpath + JDK 25 Leyden AOTCache."
    )
    parser.add_argument("--jar", required=True, help="Path to folia-bundler-*.jar")
    parser.add_argument("--workdir", default=".", help="Server working directory (default: current directory)")
    parser.add_argument("--aot-dir", default=None, help="Directory for .aot cache files (default: <workdir>/.goldleaf-aot)")
    parser.add_argument("--java", default="java", help="Path to java binary (default: java)")
    parser.add_argument(
        "--jvm-arg",
        action="append",
        default=[],
        help="Repeatable JVM flag (e.g. --jvm-arg=-Xms2G --jvm-arg=-Xmx2G)",
    )
    parser.add_argument("--no-aot", action="store_true", help="Disable Leyden AOTCache (still uses direct classpath)")
    parser.add_argument("--retrain", action="store_true", help="Force retraining the AOTCache archive")
    parser.add_argument(
        "--train-now",
        action="store_true",
        help="Run an automated boot->stop training pass first if AOT cache is absent or --retrain is set",
    )
    parser.add_argument(
        "--train-only",
        action="store_true",
        help="Exit after training completes (implies --train-now)",
    )
    parser.add_argument("--train-timeout", type=int, default=180, help="Timeout in seconds for --train-now (default: 180)")
    parser.add_argument("--print-cmd", action="store_true", help="Print resolved launch command and exit without running")
    parser.add_argument(
        "server_args",
        nargs=argparse.REMAINDER,
        help="Arguments passed to GoldLeaf (prefix with --, e.g. -- --nogui)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    jar_path = pathlib.Path(args.jar).resolve()
    if not jar_path.is_file():
        print(f"[GoldLeaf Launcher] ERROR: Bundler JAR not found: {jar_path}", file=sys.stderr)
        return 2

    workdir = pathlib.Path(args.workdir).resolve()
    aot_dir = pathlib.Path(args.aot_dir).resolve() if args.aot_dir else (workdir / ".goldleaf-aot")
    server_args = list(args.server_args)
    if server_args and server_args[0] == "--":
        server_args = server_args[1:]
    if not server_args:
        server_args = ["--nogui"]

    manifest = parse_bundler_manifest(jar_path)
    bundler_sha = sha256_file(jar_path)
    java_major, java_version_line = detect_java_version(args.java)
    cp_jars = ensure_unpacked(args.java, jar_path, bundler_sha, workdir, manifest)
    classpath = os.pathsep.join(str(p) for p in cp_jars)

    use_aot = not args.no_aot
    if use_aot and java_major < MIN_LEYDEN_JAVA_MAJOR:
        print(
            f"[GoldLeaf Launcher] WARN: Java {java_major} detected ({java_version_line}); "
            f"Leyden -XX:AOTCache requires JDK {MIN_LEYDEN_JAVA_MAJOR}+. Falling back to direct-CP JIT.",
            file=sys.stderr,
        )
        use_aot = False

    jvm_args = normalize_jvm_args_for_key(list(args.jvm_arg))
    cache_key = compute_cache_key(bundler_sha, java_version_line, jvm_args, manifest.main_class)
    final_aot = aot_dir / f"goldleaf-{cache_key}.aot"
    meta_path = aot_dir / f"goldleaf-{cache_key}.meta.json"
    meta_payload: dict[str, object] = {
        "cache_key": cache_key,
        "bundler_sha256": bundler_sha,
        "java_version": java_version_line,
        "jvm_args": jvm_args,
        "main_class": manifest.main_class,
        "created_at": time.time(),
    }

    has_cache = use_aot and (not args.retrain) and is_valid_aot_cache(final_aot, meta_path, cache_key)
    if use_aot and (args.train_now or args.train_only) and not has_cache:
        aot_dir.mkdir(parents=True, exist_ok=True)
        tmp_aot = aot_dir / f".goldleaf-{cache_key}-{os.getpid()}.aot.tmp"
        train_cmd = [args.java, *jvm_args, f"-XX:AOTCacheOutput={tmp_aot}", "-cp", classpath, manifest.main_class, *server_args]
        if args.print_cmd:
            print(shlex.join(train_cmd))
            return 0
        print(f"[GoldLeaf Launcher] Training Leyden AOTCache ({cache_key})...", file=sys.stderr)
        try:
            rc = run_headless_training(train_cmd, workdir, args.train_timeout)
        except BaseException:
            cleanup_aot_temps(tmp_aot)
            raise
        if rc != 0:
            cleanup_aot_temps(tmp_aot)
            print(f"[GoldLeaf Launcher] WARN: AOT training exited with rc={rc}; discarding temp cache.", file=sys.stderr)
            return rc
        if promote_aot_atomically(tmp_aot, final_aot, meta_path, meta_payload):
            size_mib = final_aot.stat().st_size / (1024 * 1024)
            print(f"[GoldLeaf Launcher] Promoted AOTCache {final_aot.name} ({size_mib:.1f} MiB).", file=sys.stderr)
            has_cache = True
        else:
            print("[GoldLeaf Launcher] WARN: AOT training produced no archive; continuing without AOT.", file=sys.stderr)
        if args.train_only:
            return 0

    if not use_aot:
        cmd = [args.java, *jvm_args, "-cp", classpath, manifest.main_class, *server_args]
        if args.print_cmd:
            print(shlex.join(cmd))
            return 0
        return subprocess.run(cmd, cwd=str(workdir), check=False).returncode

    if has_cache:
        cmd = [args.java, *jvm_args, f"-XX:AOTCache={final_aot}", "-cp", classpath, manifest.main_class, *server_args]
        if args.print_cmd:
            print(shlex.join(cmd))
            return 0
        return subprocess.run(cmd, cwd=str(workdir), check=False).returncode

    # Live-run training: serve normally and promote atomically when the server stops cleanly (rc == 0)
    aot_dir.mkdir(parents=True, exist_ok=True)
    tmp_aot = aot_dir / f".goldleaf-{cache_key}-{os.getpid()}.aot.tmp"
    cmd = [args.java, *jvm_args, f"-XX:AOTCacheOutput={tmp_aot}", "-cp", classpath, manifest.main_class, *server_args]
    if args.print_cmd:
        print(shlex.join(cmd))
        return 0
    print(f"[GoldLeaf Launcher] Cold AOT run ({cache_key}); archive will be promoted on clean /stop.", file=sys.stderr)
    try:
        rc = subprocess.run(cmd, cwd=str(workdir), check=False).returncode
    except BaseException:
        cleanup_aot_temps(tmp_aot)
        raise
    if rc == 0:
        promote_aot_atomically(tmp_aot, final_aot, meta_path, meta_payload)
    else:
        cleanup_aot_temps(tmp_aot)
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
