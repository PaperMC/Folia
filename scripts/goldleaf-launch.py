#!/usr/bin/env python3
"""GoldLeaf direct-classpath JDK 25 Leyden AOT launcher (goldleaf-ADR-016 Layer A).

Unpacks Paperclip's versions/ and libraries/ once per bundler SHA-256, constructs the exact
META-INF/versions.list + META-INF/libraries.list classpath order, and manages a content-addressed
JDK 25 -XX:AOTCache archive with explicit 2-step AOTMode=record/create and atomic promotion.

Why 2-step + warm-world priming?
Training on a brand-new world runs spawn chunk worldgen inside -XX:AOTMode=record, pushing JDK 25's
ReferencedKeySet ConcurrentHashMap past its 32,768*0.75 resize threshold to 65,536 buckets
(262,160 bytes), which exceeds OpenJDK 25's 256 KiB (262,144 B) archived-heap single-object cap.
Training against an existing (or disposable pre-primed) warm world stays below the cap and retains
full AOTClassLinking (~2.6s warm boot vs ~7.1s stock).
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
import tempfile
import time
import zipfile
from dataclasses import dataclass

DEFAULT_MAIN_CLASS = "org.bukkit.craftbukkit.Main"
MIN_LEYDEN_JAVA_MAJOR = 25
DISPOSABLE_SERVER_ARGS = ("--nogui", "--nojline")
AOT_CONTROL_PREFIXES = (
    "-XX:AOTCache=",
    "-XX:AOTCacheOutput=",
    "-XX:AOTMode=",
    "-XX:AOTConfiguration=",
)


@dataclass(frozen=True)
class BundlerManifest:
    main_class: str
    version_jars: tuple[str, ...]
    library_jars: tuple[str, ...]


@dataclass(frozen=True)
class TrainingOutcome:
    ok: bool
    mode: str
    aot_class_linking: bool
    detail: str = ""


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
        if any(arg.startswith(prefix) for prefix in AOT_CONTROL_PREFIXES):
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

    print(
        "[GoldLeaf Launcher] Unpacking server JAR libraries (one-time per bundler build)...",
        file=sys.stderr,
    )
    t_unpack = time.monotonic()
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
    print(
        f"[GoldLeaf Launcher] Unpacked {len(cp_jars)} classpath JARs in {time.monotonic() - t_unpack:.1f}s.",
        file=sys.stderr,
    )
    tmp_stamp = workdir / f".goldleaf-unpacked-{os.getpid()}.json.tmp"
    tmp_stamp.write_text(
        json.dumps({"bundler_sha256": bundler_sha256, "unpacked_at": time.time()}, indent=2) + "\n",
        encoding="utf-8",
    )
    os.replace(tmp_stamp, stamp_path)
    return cp_jars


def is_eula_accepted(workdir: pathlib.Path) -> bool:
    eula_path = workdir / "eula.txt"
    if not eula_path.is_file():
        return False
    try:
        content = eula_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    for raw_line in content.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip().lower() == "eula" and value.strip().lower() == "true":
            return True
    return False


def _read_level_name_from_properties(workdir: pathlib.Path) -> str:
    props_path = workdir / "server.properties"
    if not props_path.is_file():
        return "world"
    try:
        content = props_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return "world"
    for raw_line in content.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == "level-name" and value.strip():
            return value.strip()
    return "world"


def has_existing_world(workdir: pathlib.Path, server_args: list[str]) -> bool:
    universe = workdir
    level_name = _read_level_name_from_properties(workdir)
    idx = 0
    while idx < len(server_args):
        arg = server_args[idx]
        if arg in ("--world", "-W", "--level-name") and idx + 1 < len(server_args):
            level_name = server_args[idx + 1]
            idx += 2
            continue
        if arg.startswith("--world=") or arg.startswith("--level-name="):
            level_name = arg.split("=", 1)[1]
            idx += 1
            continue
        if arg in ("--universe", "-U") and idx + 1 < len(server_args):
            u_path = pathlib.Path(server_args[idx + 1])
            universe = u_path if u_path.is_absolute() else (workdir / u_path)
            idx += 2
            continue
        if arg.startswith("--universe="):
            u_path = pathlib.Path(arg.split("=", 1)[1])
            universe = u_path if u_path.is_absolute() else (workdir / u_path)
            idx += 1
            continue
        idx += 1

    world_path = pathlib.Path(level_name)
    world_dir = world_path if world_path.is_absolute() else (universe / world_path)
    return (world_dir / "level.dat").is_file()


def is_aot_heap_limit_error(output: str) -> bool:
    return "Cannot archive the sub-graph" in output


def _summarize_aot_error(output: str) -> str:
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    err_lines = [line for line in lines if "[error" in line.lower() or "error" in line.lower()]
    chosen = err_lines[-4:] if err_lines else lines[-4:]
    return " | ".join(chosen)


def prepare_disposable_train_dir(scratch_dir: pathlib.Path, workdir: pathlib.Path) -> pathlib.Path:
    scratch_dir.mkdir(parents=True, exist_ok=True)
    for subdir in ("versions", "libraries"):
        src = workdir / subdir
        dst = scratch_dir / subdir
        if src.exists() and not dst.exists():
            try:
                dst.symlink_to(src)
            except OSError:
                pass
    (scratch_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (scratch_dir / "server.properties").write_text(
        "online-mode=false\nserver-port=0\nlevel-seed=0\nspawn-protection=0\n",
        encoding="utf-8",
    )
    cfg_dir = scratch_dir / "config"
    cfg_dir.mkdir(exist_ok=True)
    (cfg_dir / "goldleaf.yml").write_text(
        "worldgen:\n  opencl-gpu-enabled: false\n",
        encoding="utf-8",
    )
    return scratch_dir


def read_aot_metadata(meta_path: pathlib.Path) -> dict[str, object]:
    if not meta_path.is_file():
        return {}
    try:
        data = json.loads(meta_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def is_valid_aot_cache(aot_path: pathlib.Path, meta_path: pathlib.Path, expected_key: str) -> bool:
    if not aot_path.is_file() or aot_path.stat().st_size <= 0 or not meta_path.is_file():
        return False
    return read_aot_metadata(meta_path).get("cache_key") == expected_key


def cleanup_aot_temps(tmp_aot: pathlib.Path, tmp_cfg: pathlib.Path | None = None) -> None:
    tmp_aot.unlink(missing_ok=True)
    pathlib.Path(f"{tmp_aot}.config").unlink(missing_ok=True)
    if tmp_cfg is not None:
        tmp_cfg.unlink(missing_ok=True)


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


def run_headless_boot(
    cmd: list[str],
    workdir: pathlib.Path,
    timeout_sec: int,
    *,
    stop_on_done: bool = True,
) -> int:
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
            if stop_on_done and not sent_stop and "Done (" in line and "For help, type" in line:
                sent_stop = True
                proc.stdin.write("stop\n")
                proc.stdin.flush()
            if time.monotonic() > deadline:
                proc.terminate()
                try:
                    proc.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=5)
                raise TimeoutError(f"AOT training exceeded {timeout_sec}s timeout")
        return proc.wait(timeout=max(10, int(deadline - time.monotonic())))
    except KeyboardInterrupt:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)
        raise


def run_headless_training(cmd: list[str], workdir: pathlib.Path, timeout_sec: int) -> int:
    return run_headless_boot(cmd, workdir, timeout_sec, stop_on_done=True)


def run_aot_create(
    java_bin: str,
    jvm_args: list[str],
    classpath: str,
    cfg_path: pathlib.Path,
    out_aot: pathlib.Path,
    timeout_sec: int,
    extra_args: list[str] | None = None,
) -> tuple[int, str]:
    cmd = [
        java_bin,
        *jvm_args,
        *(extra_args or []),
        "-XX:AOTMode=create",
        f"-XX:AOTConfiguration={cfg_path}",
        f"-XX:AOTCache={out_aot}",
        "-cp",
        classpath,
    ]
    proc = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=max(30, timeout_sec),
        check=False,
    )
    output = proc.stdout or ""
    if proc.returncode != 0:
        out_aot.unlink(missing_ok=True)
    return proc.returncode, output


def _retry_without_class_linking(
    java_bin: str,
    jvm_args: list[str],
    classpath: str,
    cfg_path: pathlib.Path,
    tmp_aot: pathlib.Path,
    timeout_sec: int,
    *,
    mode_label: str,
) -> TrainingOutcome:
    print(
        "[GoldLeaf Launcher] WARN: AOTMode=create hit JDK 25's 256 KiB archived-heap object cap "
        "(ReferencedKeySet overflow from worldgen/heavy load); retrying with -XX:-AOTClassLinking.",
        file=sys.stderr,
    )
    retry_rc, retry_out = run_aot_create(
        java_bin,
        jvm_args,
        classpath,
        cfg_path,
        tmp_aot,
        timeout_sec,
        extra_args=["-XX:-AOTClassLinking"],
    )
    if retry_rc == 0 and tmp_aot.is_file() and tmp_aot.stat().st_size > 0:
        return TrainingOutcome(
            ok=True,
            mode=f"{mode_label}-no-class-linking",
            aot_class_linking=False,
        )
    return TrainingOutcome(
        ok=False,
        mode=f"{mode_label}-no-class-linking",
        aot_class_linking=False,
        detail=_summarize_aot_error(retry_out),
    )


def finalize_recorded_aot_config(
    java_bin: str,
    jvm_args: list[str],
    classpath: str,
    cfg_path: pathlib.Path,
    tmp_aot: pathlib.Path,
    timeout_sec: int,
    *,
    mode_label: str,
    allow_no_class_linking_fallback: bool = True,
) -> TrainingOutcome:
    if not cfg_path.is_file() or cfg_path.stat().st_size <= 0:
        return TrainingOutcome(
            ok=False,
            mode=mode_label,
            aot_class_linking=True,
            detail=f"AOT configuration was not created: {cfg_path}",
        )

    print(
        "[GoldLeaf Launcher] Assembling Leyden AOTCache archive (~5-10s)...",
        file=sys.stderr,
    )
    t_create = time.monotonic()
    rc, output = run_aot_create(
        java_bin, jvm_args, classpath, cfg_path, tmp_aot, timeout_sec, extra_args=None
    )
    if rc == 0 and tmp_aot.is_file() and tmp_aot.stat().st_size > 0:
        print(
            f"[GoldLeaf Launcher] Assembled Leyden AOTCache in {time.monotonic() - t_create:.1f}s.",
            file=sys.stderr,
        )
        return TrainingOutcome(ok=True, mode=mode_label, aot_class_linking=True)

    if is_aot_heap_limit_error(output) and allow_no_class_linking_fallback:
        return _retry_without_class_linking(
            java_bin, jvm_args, classpath, cfg_path, tmp_aot, timeout_sec, mode_label=mode_label
        )

    return TrainingOutcome(
        ok=False,
        mode=mode_label,
        aot_class_linking=True,
        detail=_summarize_aot_error(output),
    )


def _record_init_settings_config(
    java_bin: str,
    jvm_args: list[str],
    classpath: str,
    main_class: str,
    workdir: pathlib.Path,
    scratch_root: pathlib.Path,
    cfg_path: pathlib.Path,
    timeout_sec: int,
) -> int:
    init_dir = prepare_disposable_train_dir(scratch_root / "init", workdir)
    cmd = [
        java_bin,
        *jvm_args,
        "-XX:AOTMode=record",
        f"-XX:AOTConfiguration={cfg_path}",
        "-cp",
        classpath,
        main_class,
        *DISPOSABLE_SERVER_ARGS,
        "--initSettings",
    ]
    return run_headless_boot(cmd, init_dir, timeout_sec, stop_on_done=False)


def train_aot_cache(
    java_bin: str,
    jvm_args: list[str],
    classpath: str,
    main_class: str,
    server_args: list[str],
    workdir: pathlib.Path,
    aot_dir: pathlib.Path,
    tmp_aot: pathlib.Path,
    timeout_sec: int,
    train_mode: str = "auto",
) -> TrainingOutcome:
    aot_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".goldleaf-train-", dir=str(aot_dir)) as tmp_dir_str:
        scratch_root = pathlib.Path(tmp_dir_str)

        if train_mode == "init":
            init_cfg = scratch_root / "init.aotconfig"
            print(
                "[GoldLeaf Launcher] Recording AOT startup profile via --initSettings...",
                file=sys.stderr,
            )
            t_rec = time.monotonic()
            rc = _record_init_settings_config(
                java_bin, jvm_args, classpath, main_class, workdir, scratch_root, init_cfg, timeout_sec
            )
            if rc != 0:
                return TrainingOutcome(
                    ok=False,
                    mode="init",
                    aot_class_linking=True,
                    detail=f"--initSettings record exited with rc={rc}",
                )
            print(
                f"[GoldLeaf Launcher] Recorded --initSettings AOT profile in {time.monotonic() - t_rec:.1f}s.",
                file=sys.stderr,
            )
            return finalize_recorded_aot_config(
                java_bin, jvm_args, classpath, init_cfg, tmp_aot, timeout_sec, mode_label="init"
            )

        # Warm-world training ("auto" or "warm"): use existing world when ready, else prime a
        # disposable world in scratch_root so we never pollute workdir or hit cold-worldgen overflow.
        warm_cfg = scratch_root / "warm.aotconfig"
        if is_eula_accepted(workdir) and has_existing_world(workdir, server_args):
            mode_label = "warm"
            record_cwd = workdir
            record_server_args = server_args
            print(
                "[GoldLeaf Launcher] Recording AOT startup profile against existing warm world "
                "(server will boot and stop automatically)...",
                file=sys.stderr,
            )
        else:
            mode_label = "disposable-warm"
            disposable_dir = prepare_disposable_train_dir(scratch_root / "warm-world", workdir)
            record_cwd = disposable_dir
            record_server_args = list(DISPOSABLE_SERVER_ARGS)
            print(
                "[GoldLeaf Launcher] Priming temporary warm world so AOT training avoids "
                "cold-worldgen heap limits (workdir stays untouched)...",
                file=sys.stderr,
            )
            t_prime = time.monotonic()
            prime_cmd = [java_bin, *jvm_args, "-cp", classpath, main_class, *record_server_args]
            prime_rc = run_headless_boot(prime_cmd, disposable_dir, timeout_sec, stop_on_done=True)
            if prime_rc != 0:
                return TrainingOutcome(
                    ok=False,
                    mode=mode_label,
                    aot_class_linking=True,
                    detail=f"Disposable warm-world prime exited with rc={prime_rc}",
                )
            print(
                f"[GoldLeaf Launcher] Temporary warm world primed in {time.monotonic() - t_prime:.1f}s; "
                "recording AOT startup profile (server will boot and stop automatically)...",
                file=sys.stderr,
            )

        t_rec = time.monotonic()
        record_cmd = [
            java_bin,
            *jvm_args,
            "-XX:AOTMode=record",
            f"-XX:AOTConfiguration={warm_cfg}",
            "-cp",
            classpath,
            main_class,
            *record_server_args,
        ]
        record_rc = run_headless_boot(record_cmd, record_cwd, timeout_sec, stop_on_done=True)
        if record_rc != 0:
            return TrainingOutcome(
                ok=False,
                mode=mode_label,
                aot_class_linking=True,
                detail=f"AOTMode=record exited with rc={record_rc}",
            )
        print(
            f"[GoldLeaf Launcher] Recorded AOT profile in {time.monotonic() - t_rec:.1f}s; "
            "assembling Leyden AOTCache archive (~5-10s)...",
            file=sys.stderr,
        )
        t_create = time.monotonic()
        create_rc, create_out = run_aot_create(
            java_bin, jvm_args, classpath, warm_cfg, tmp_aot, timeout_sec, extra_args=None
        )
        if create_rc == 0 and tmp_aot.is_file() and tmp_aot.stat().st_size > 0:
            print(
                f"[GoldLeaf Launcher] Assembled Leyden AOTCache in {time.monotonic() - t_create:.1f}s.",
                file=sys.stderr,
            )
            return TrainingOutcome(ok=True, mode=mode_label, aot_class_linking=True)

        if is_aot_heap_limit_error(create_out):
            if train_mode == "auto":
                print(
                    "[GoldLeaf Launcher] WARN: Warm-world AOTMode=create hit JDK 25's 256 KiB "
                    "archived-heap object cap; falling back to --initSettings full AOTClassLinking.",
                    file=sys.stderr,
                )
                init_cfg = scratch_root / "init-fallback.aotconfig"
                init_rc = _record_init_settings_config(
                    java_bin,
                    jvm_args,
                    classpath,
                    main_class,
                    workdir,
                    scratch_root,
                    init_cfg,
                    timeout_sec,
                )
                if init_rc == 0 and init_cfg.is_file() and init_cfg.stat().st_size > 0:
                    print(
                        "[GoldLeaf Launcher] Assembling --initSettings AOTCache archive (~5-8s)...",
                        file=sys.stderr,
                    )
                    init_create_rc, _ = run_aot_create(
                        java_bin, jvm_args, classpath, init_cfg, tmp_aot, timeout_sec, extra_args=None
                    )
                    if init_create_rc == 0 and tmp_aot.is_file() and tmp_aot.stat().st_size > 0:
                        return TrainingOutcome(ok=True, mode="init-fallback", aot_class_linking=True)

            # Last resort: retain recorded warm classes without AOTClassLinking archived heap
            return _retry_without_class_linking(
                java_bin,
                jvm_args,
                classpath,
                warm_cfg,
                tmp_aot,
                timeout_sec,
                mode_label=mode_label,
            )

        return TrainingOutcome(
            ok=False,
            mode=mode_label,
            aot_class_linking=True,
            detail=_summarize_aot_error(create_out),
        )


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
    parser.add_argument(
        "--train-mode",
        choices=("auto", "warm", "init"),
        default="auto",
        help=(
            "AOT training strategy for --train-now/--train-only (default: auto). "
            "auto/warm train against an existing or disposable pre-primed warm world for full "
            "AOTClassLinking (~2.6s boot); init trains via --initSettings (~15s train, ~3.5s boot)."
        ),
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
        if args.print_cmd:
            tmp_cfg = aot_dir / f".goldleaf-{cache_key}-{os.getpid()}.aotconfig"
            preview_args = [*server_args, "--initSettings"] if args.train_mode == "init" else server_args
            train_cmd = [
                args.java,
                *jvm_args,
                "-XX:AOTMode=record",
                f"-XX:AOTConfiguration={tmp_cfg}",
                "-cp",
                classpath,
                manifest.main_class,
                *preview_args,
            ]
            print(shlex.join(train_cmd))
            return 0
        train_reason = "--retrain requested" if args.retrain else "no cached archive for this JAR/JVM/flag set"
        print(
            f"[GoldLeaf Launcher] One-time Leyden AOT training ({cache_key}, mode={args.train_mode}; "
            f"{train_reason}) — this adds ~15-35s now so future startups are ~2-3x faster.",
            file=sys.stderr,
        )
        t_train_total = time.monotonic()
        try:
            outcome = train_aot_cache(
                java_bin=args.java,
                jvm_args=jvm_args,
                classpath=classpath,
                main_class=manifest.main_class,
                server_args=server_args,
                workdir=workdir,
                aot_dir=aot_dir,
                tmp_aot=tmp_aot,
                timeout_sec=args.train_timeout,
                train_mode=args.train_mode,
            )
        except BaseException:
            cleanup_aot_temps(tmp_aot)
            raise
        if outcome.ok:
            meta_payload["train_mode"] = outcome.mode
            meta_payload["aot_class_linking"] = outcome.aot_class_linking
            if promote_aot_atomically(tmp_aot, final_aot, meta_path, meta_payload):
                size_mib = final_aot.stat().st_size / (1024 * 1024)
                print(
                    f"[GoldLeaf Launcher] Promoted AOTCache {final_aot.name} "
                    f"({size_mib:.1f} MiB, mode={outcome.mode}, classLinking={outcome.aot_class_linking}) "
                    f"in {time.monotonic() - t_train_total:.1f}s.",
                    file=sys.stderr,
                )
                has_cache = True
            else:
                print(
                    "[GoldLeaf Launcher] ERROR: AOT training reported success but produced no archive.",
                    file=sys.stderr,
                )
        else:
            cleanup_aot_temps(tmp_aot)
            detail_suffix = f": {outcome.detail}" if outcome.detail else ""
            print(
                f"[GoldLeaf Launcher] ERROR: AOT training failed (mode={outcome.mode}){detail_suffix}",
                file=sys.stderr,
            )
        if args.train_only:
            return 0 if has_cache else 1

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
        cached_meta = read_aot_metadata(meta_path)
        cached_mode = cached_meta.get("train_mode", "cached")
        cached_mib = final_aot.stat().st_size / (1024 * 1024)
        print(
            f"[GoldLeaf Launcher] Using cached Leyden AOTCache {final_aot.name} "
            f"({cached_mib:.1f} MiB, mode={cached_mode}).",
            file=sys.stderr,
        )
        return subprocess.run(cmd, cwd=str(workdir), check=False).returncode

    # Live-run training (2-step): serve normally under AOTMode=record and assemble AOTCache on clean /stop
    aot_dir.mkdir(parents=True, exist_ok=True)
    tmp_aot = aot_dir / f".goldleaf-{cache_key}-{os.getpid()}.aot.tmp"
    tmp_cfg = aot_dir / f".goldleaf-{cache_key}-{os.getpid()}.aotconfig"
    cmd = [
        args.java,
        *jvm_args,
        "-XX:AOTMode=record",
        f"-XX:AOTConfiguration={tmp_cfg}",
        "-cp",
        classpath,
        manifest.main_class,
        *server_args,
    ]
    if args.print_cmd:
        print(shlex.join(cmd))
        return 0
    print(
        f"[GoldLeaf Launcher] First run for this JAR/JVM/flag set ({cache_key}): recording AOT profile "
        "— this boot runs at normal speed and will assemble the cache on clean /stop (~5-10s).",
        file=sys.stderr,
    )
    try:
        rc = subprocess.run(cmd, cwd=str(workdir), check=False).returncode
        if rc == 0:
            outcome = finalize_recorded_aot_config(
                java_bin=args.java,
                jvm_args=jvm_args,
                classpath=classpath,
                cfg_path=tmp_cfg,
                tmp_aot=tmp_aot,
                timeout_sec=args.train_timeout,
                mode_label="live",
                allow_no_class_linking_fallback=True,
            )
            if outcome.ok:
                meta_payload["train_mode"] = outcome.mode
                meta_payload["aot_class_linking"] = outcome.aot_class_linking
                if promote_aot_atomically(tmp_aot, final_aot, meta_path, meta_payload):
                    size_mib = final_aot.stat().st_size / (1024 * 1024)
                    print(
                        f"[GoldLeaf Launcher] Promoted AOTCache {final_aot.name} "
                        f"({size_mib:.1f} MiB, mode={outcome.mode}, classLinking={outcome.aot_class_linking}).",
                        file=sys.stderr,
                    )
                    if not outcome.aot_class_linking:
                        print(
                            "[GoldLeaf Launcher] TIP: Run once with --train-now --retrain on your warm world "
                            "to upgrade to full AOTClassLinking (~2.6s boot).",
                            file=sys.stderr,
                        )
            else:
                detail_suffix = f": {outcome.detail}" if outcome.detail else ""
                print(
                    f"[GoldLeaf Launcher] WARN: AOT assembly failed after /stop{detail_suffix}",
                    file=sys.stderr,
                )
    finally:
        cleanup_aot_temps(tmp_aot, tmp_cfg)
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
