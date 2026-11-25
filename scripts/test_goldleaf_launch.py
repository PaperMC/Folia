#!/usr/bin/env python3
"""Unit tests for GoldLeaf/scripts/goldleaf-launch.py (goldleaf-ADR-016 Layer A)."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

MODULE_PATH = pathlib.Path(__file__).with_name("goldleaf-launch.py")
SPEC = importlib.util.spec_from_file_location("goldleaf_launch", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
LAUNCHER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = LAUNCHER
SPEC.loader.exec_module(LAUNCHER)


def _create_dummy_bundler(jar_path: pathlib.Path) -> None:
    with zipfile.ZipFile(jar_path, "w") as zf:
        zf.writestr("META-INF/main-class", "org.bukkit.craftbukkit.Main\n")
        zf.writestr("META-INF/versions.list", "abc\t26.3\t26.3/goldleaf-26.3.jar\n")
        zf.writestr(
            "META-INF/libraries.list",
            "h1\ta:b:1\ta/b/1/b-1.jar\nh2\tc:d:2\tc/d/2/d-2.jar\n",
        )


class GoldLeafLauncherTest(unittest.TestCase):
    def test_parse_bundler_manifest_preserves_exact_paperclip_order(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            jar = pathlib.Path(tmp) / "bundler.jar"
            _create_dummy_bundler(jar)

            manifest = LAUNCHER.parse_bundler_manifest(jar)
            self.assertEqual(manifest.main_class, "org.bukkit.craftbukkit.Main")
            self.assertEqual(manifest.version_jars, ("26.3/goldleaf-26.3.jar",))
            self.assertEqual(manifest.library_jars, ("a/b/1/b-1.jar", "c/d/2/d-2.jar"))

            cp = LAUNCHER.build_direct_classpath(pathlib.Path(tmp), manifest)
            self.assertEqual(
                [p.name for p in cp],
                ["goldleaf-26.3.jar", "b-1.jar", "d-2.jar"],
            )

    def test_parse_java_major_handles_jdk25_and_legacy_formats(self) -> None:
        self.assertEqual(LAUNCHER.parse_java_major('openjdk version "25.0.4.1" 2026-07-21'), 25)
        self.assertEqual(LAUNCHER.parse_java_major('java version "21.0.3"'), 21)
        self.assertEqual(LAUNCHER.parse_java_major('java version "1.8.0_402"'), 8)
        self.assertEqual(LAUNCHER.parse_java_major("unrecognized"), 0)

    def test_cache_key_ignores_aot_flags_and_changes_on_heap_or_digest(self) -> None:
        k1 = LAUNCHER.compute_cache_key("shaA", "openjdk 25", ["-Xms2G", "-Xmx2G"], "Main")
        k2 = LAUNCHER.compute_cache_key(
            "shaA",
            "openjdk 25",
            [
                "-Xms2G",
                "-XX:AOTCache=old.aot",
                "-XX:AOTCacheOutput=tmp.aot",
                "-XX:AOTMode=record",
                "-XX:AOTConfiguration=tmp.aotconfig",
                "-Xmx2G",
            ],
            "Main",
        )
        k3 = LAUNCHER.compute_cache_key("shaA", "openjdk 25", ["-Xms4G", "-Xmx4G"], "Main")
        k4 = LAUNCHER.compute_cache_key("shaB", "openjdk 25", ["-Xms2G", "-Xmx2G"], "Main")
        self.assertEqual(k1, k2)
        self.assertNotEqual(k1, k3)
        self.assertNotEqual(k1, k4)

    def test_atomic_aot_promotion_and_validation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            aot_dir = pathlib.Path(tmp) / ".goldleaf-aot"
            aot_dir.mkdir()
            tmp_aot = aot_dir / ".goldleaf-abc.aot.tmp"
            final_aot = aot_dir / "goldleaf-abc.aot"
            meta_path = aot_dir / "goldleaf-abc.meta.json"

            # Empty temp file must be rejected and cleaned up
            tmp_aot.write_bytes(b"")
            self.assertFalse(
                LAUNCHER.promote_aot_atomically(tmp_aot, final_aot, meta_path, {"cache_key": "abc"})
            )
            self.assertFalse(tmp_aot.exists())
            self.assertFalse(final_aot.exists())

            # Non-empty temp file is promoted atomically alongside meta.json
            tmp_aot.write_bytes(b"LEYDEN_AOT_BYTES")
            self.assertTrue(
                LAUNCHER.promote_aot_atomically(tmp_aot, final_aot, meta_path, {"cache_key": "abc"})
            )
            self.assertFalse(tmp_aot.exists())
            self.assertTrue(final_aot.is_file())
            self.assertTrue(LAUNCHER.is_valid_aot_cache(final_aot, meta_path, "abc"))
            self.assertFalse(LAUNCHER.is_valid_aot_cache(final_aot, meta_path, "wrong-key"))
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            self.assertEqual(meta["cache_key"], "abc")

    def test_world_and_eula_detection(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            wd = pathlib.Path(tmp)
            self.assertFalse(LAUNCHER.is_eula_accepted(wd))
            self.assertFalse(LAUNCHER.has_existing_world(wd, ["--nogui"]))

            (wd / "eula.txt").write_text("# comment\neula=false\n", encoding="utf-8")
            self.assertFalse(LAUNCHER.is_eula_accepted(wd))
            (wd / "eula.txt").write_text("# comment\neula = TRUE\n", encoding="utf-8")
            self.assertTrue(LAUNCHER.is_eula_accepted(wd))

            (wd / "server.properties").write_text("level-name=myworld\n", encoding="utf-8")
            self.assertFalse(LAUNCHER.has_existing_world(wd, ["--nogui"]))
            (wd / "myworld").mkdir()
            (wd / "myworld" / "level.dat").write_bytes(b"nbt")
            self.assertTrue(LAUNCHER.has_existing_world(wd, ["--nogui"]))

            # CLI --world / --universe overrides server.properties
            self.assertFalse(LAUNCHER.has_existing_world(wd, ["--world", "other"]))
            (wd / "worlds" / "other").mkdir(parents=True)
            (wd / "worlds" / "other" / "level.dat").write_bytes(b"nbt")
            self.assertTrue(
                LAUNCHER.has_existing_world(wd, ["--universe", "worlds", "-W", "other"])
            )

    def test_is_aot_heap_limit_error(self) -> None:
        heap_err = (
            "[2.152s][error  ][aot,heap   ] Cannot archive the sub-graph referenced from "
            "[Ljava.util.concurrent.ConcurrentHashMap$Node; object (0x000000008184eb38) "
            "size 262160, skipped.\n"
        )
        self.assertTrue(LAUNCHER.is_aot_heap_limit_error(heap_err))
        self.assertFalse(LAUNCHER.is_aot_heap_limit_error("Error: Could not find main class"))

    def test_prepare_disposable_train_dir_isolates_workdir(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            wd = root / "server"
            (wd / "versions").mkdir(parents=True)
            (wd / "libraries").mkdir(parents=True)
            scratch = root / "scratch"
            scratch.mkdir()

            LAUNCHER.prepare_disposable_train_dir(scratch, wd)
            self.assertTrue(LAUNCHER.is_eula_accepted(scratch))
            props = (scratch / "server.properties").read_text(encoding="utf-8")
            self.assertIn("server-port=0", props)
            self.assertIn("online-mode=false", props)
            cfg = (scratch / "config" / "goldleaf.yml").read_text(encoding="utf-8")
            self.assertIn("opencl-gpu-enabled: false", cfg)
            self.assertFalse((wd / "eula.txt").exists())
            self.assertFalse((wd / "server.properties").exists())

    def test_two_step_train_fresh_workdir_uses_disposable_prime_and_record(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            wd = pathlib.Path(tmp) / "fresh_server"
            (wd / "versions").mkdir(parents=True)
            (wd / "libraries").mkdir(parents=True)
            aot_dir = wd / ".goldleaf-aot"
            aot_dir.mkdir()
            tmp_aot = aot_dir / ".test.aot.tmp"

            boot_calls: list[tuple[list[str], pathlib.Path, bool]] = []

            def fake_boot(
                cmd: list[str], cwd: pathlib.Path, timeout_sec: int, *, stop_on_done: bool = True
            ) -> int:
                boot_calls.append((cmd, cwd, stop_on_done))
                for arg in cmd:
                    if arg.startswith("-XX:AOTConfiguration="):
                        pathlib.Path(arg.split("=", 1)[1]).write_bytes(b"AOTCFG")
                return 0

            def fake_create(
                java_bin: str,
                jvm_args: list[str],
                classpath: str,
                cfg_path: pathlib.Path,
                out_aot: pathlib.Path,
                timeout_sec: int,
                extra_args: list[str] | None = None,
            ) -> tuple[int, str]:
                self.assertTrue(cfg_path.is_file())
                self.assertFalse(extra_args)
                out_aot.write_bytes(b"FULL_AOT")
                return 0, "AOTCache creation is complete"

            with (
                mock.patch.object(LAUNCHER, "run_headless_boot", side_effect=fake_boot),
                mock.patch.object(LAUNCHER, "run_aot_create", side_effect=fake_create),
            ):
                outcome = LAUNCHER.train_aot_cache(
                    java_bin="java",
                    jvm_args=["-Xms2G", "-Xmx2G"],
                    classpath="/cp/a.jar",
                    main_class="org.bukkit.craftbukkit.Main",
                    server_args=["--nogui"],
                    workdir=wd,
                    aot_dir=aot_dir,
                    tmp_aot=tmp_aot,
                    timeout_sec=120,
                    train_mode="auto",
                )

            self.assertTrue(outcome.ok)
            self.assertEqual(outcome.mode, "disposable-warm")
            self.assertTrue(outcome.aot_class_linking)
            self.assertEqual(len(boot_calls), 2)
            # Pass 1: prime without AOTMode=record; Pass 2: record in same disposable dir
            self.assertNotIn("-XX:AOTMode=record", boot_calls[0][0])
            self.assertIn("-XX:AOTMode=record", boot_calls[1][0])
            self.assertEqual(boot_calls[0][1], boot_calls[1][1])
            self.assertNotEqual(boot_calls[0][1], wd)
            # Fresh workdir was never polluted
            self.assertFalse((wd / "world").exists())
            self.assertFalse((wd / "eula.txt").exists())

    def test_two_step_train_existing_world_skips_prime_pass(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            wd = pathlib.Path(tmp) / "warm_server"
            (wd / "world").mkdir(parents=True)
            (wd / "world" / "level.dat").write_bytes(b"nbt")
            (wd / "eula.txt").write_text("eula=true\n", encoding="utf-8")
            aot_dir = wd / ".goldleaf-aot"
            aot_dir.mkdir()
            tmp_aot = aot_dir / ".test.aot.tmp"

            boot_calls: list[tuple[list[str], pathlib.Path]] = []

            def fake_boot(
                cmd: list[str], cwd: pathlib.Path, timeout_sec: int, *, stop_on_done: bool = True
            ) -> int:
                boot_calls.append((cmd, cwd))
                for arg in cmd:
                    if arg.startswith("-XX:AOTConfiguration="):
                        pathlib.Path(arg.split("=", 1)[1]).write_bytes(b"AOTCFG")
                return 0

            def fake_create(
                java_bin: str,
                jvm_args: list[str],
                classpath: str,
                cfg_path: pathlib.Path,
                out_aot: pathlib.Path,
                timeout_sec: int,
                extra_args: list[str] | None = None,
            ) -> tuple[int, str]:
                out_aot.write_bytes(b"WARM_AOT")
                return 0, "ok"

            with (
                mock.patch.object(LAUNCHER, "run_headless_boot", side_effect=fake_boot),
                mock.patch.object(LAUNCHER, "run_aot_create", side_effect=fake_create),
            ):
                outcome = LAUNCHER.train_aot_cache(
                    java_bin="java",
                    jvm_args=["-Xms2G"],
                    classpath="/cp/a.jar",
                    main_class="org.bukkit.craftbukkit.Main",
                    server_args=["--nogui"],
                    workdir=wd,
                    aot_dir=aot_dir,
                    tmp_aot=tmp_aot,
                    timeout_sec=120,
                    train_mode="auto",
                )

            self.assertTrue(outcome.ok)
            self.assertEqual(outcome.mode, "warm")
            self.assertTrue(outcome.aot_class_linking)
            self.assertEqual(len(boot_calls), 1)
            self.assertEqual(boot_calls[0][1], wd)
            self.assertIn("-XX:AOTMode=record", boot_calls[0][0])

    def test_auto_mode_falls_back_to_init_settings_on_heap_limit_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            wd = pathlib.Path(tmp) / "heavy_plugin_server"
            (wd / "world").mkdir(parents=True)
            (wd / "world" / "level.dat").write_bytes(b"nbt")
            (wd / "eula.txt").write_text("eula=true\n", encoding="utf-8")
            aot_dir = wd / ".goldleaf-aot"
            aot_dir.mkdir()
            tmp_aot = aot_dir / ".test.aot.tmp"

            boot_cmds: list[list[str]] = []

            def fake_boot(
                cmd: list[str], cwd: pathlib.Path, timeout_sec: int, *, stop_on_done: bool = True
            ) -> int:
                boot_cmds.append(cmd)
                for arg in cmd:
                    if arg.startswith("-XX:AOTConfiguration="):
                        pathlib.Path(arg.split("=", 1)[1]).write_bytes(b"AOTCFG")
                return 0

            create_attempts = 0

            def fake_create(
                java_bin: str,
                jvm_args: list[str],
                classpath: str,
                cfg_path: pathlib.Path,
                out_aot: pathlib.Path,
                timeout_sec: int,
                extra_args: list[str] | None = None,
            ) -> tuple[int, str]:
                nonlocal create_attempts
                create_attempts += 1
                if create_attempts == 1:
                    return 1, "Cannot archive the sub-graph referenced from ... size 262160, skipped."
                out_aot.write_bytes(b"INIT_AOT")
                return 0, "ok"

            with (
                mock.patch.object(LAUNCHER, "run_headless_boot", side_effect=fake_boot),
                mock.patch.object(LAUNCHER, "run_aot_create", side_effect=fake_create),
            ):
                outcome = LAUNCHER.train_aot_cache(
                    java_bin="java",
                    jvm_args=["-Xms2G"],
                    classpath="/cp/a.jar",
                    main_class="org.bukkit.craftbukkit.Main",
                    server_args=["--nogui"],
                    workdir=wd,
                    aot_dir=aot_dir,
                    tmp_aot=tmp_aot,
                    timeout_sec=120,
                    train_mode="auto",
                )

            self.assertTrue(outcome.ok)
            self.assertEqual(outcome.mode, "init-fallback")
            self.assertTrue(outcome.aot_class_linking)
            self.assertEqual(len(boot_cmds), 2)
            self.assertIn("--initSettings", boot_cmds[1])

    def test_auto_mode_falls_back_to_no_class_linking_if_init_also_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            wd = pathlib.Path(tmp) / "server"
            (wd / "world").mkdir(parents=True)
            (wd / "world" / "level.dat").write_bytes(b"nbt")
            (wd / "eula.txt").write_text("eula=true\n", encoding="utf-8")
            aot_dir = wd / ".goldleaf-aot"
            aot_dir.mkdir()
            tmp_aot = aot_dir / ".test.aot.tmp"

            def fake_boot(
                cmd: list[str], cwd: pathlib.Path, timeout_sec: int, *, stop_on_done: bool = True
            ) -> int:
                for arg in cmd:
                    if arg.startswith("-XX:AOTConfiguration="):
                        pathlib.Path(arg.split("=", 1)[1]).write_bytes(b"AOTCFG")
                return 0

            def fake_create(
                java_bin: str,
                jvm_args: list[str],
                classpath: str,
                cfg_path: pathlib.Path,
                out_aot: pathlib.Path,
                timeout_sec: int,
                extra_args: list[str] | None = None,
            ) -> tuple[int, str]:
                if extra_args and "-XX:-AOTClassLinking" in extra_args:
                    out_aot.write_bytes(b"NOLINK_AOT")
                    return 0, "ok"
                return 1, "Cannot archive the sub-graph referenced from ... size 262160, skipped."

            with (
                mock.patch.object(LAUNCHER, "run_headless_boot", side_effect=fake_boot),
                mock.patch.object(LAUNCHER, "run_aot_create", side_effect=fake_create),
            ):
                outcome = LAUNCHER.train_aot_cache(
                    java_bin="java",
                    jvm_args=["-Xms2G"],
                    classpath="/cp/a.jar",
                    main_class="org.bukkit.craftbukkit.Main",
                    server_args=["--nogui"],
                    workdir=wd,
                    aot_dir=aot_dir,
                    tmp_aot=tmp_aot,
                    timeout_sec=120,
                    train_mode="auto",
                )

            self.assertTrue(outcome.ok)
            self.assertEqual(outcome.mode, "warm-no-class-linking")
            self.assertFalse(outcome.aot_class_linking)

    def test_finalize_recorded_aot_config_falls_back_on_heap_limit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            aot_dir = pathlib.Path(tmp)
            cfg_path = aot_dir / "live.aotconfig"
            cfg_path.write_bytes(b"CFG")
            tmp_aot = aot_dir / "live.aot.tmp"

            calls: list[list[str] | None] = []

            def fake_create(
                java_bin: str,
                jvm_args: list[str],
                classpath: str,
                cfg: pathlib.Path,
                out_aot: pathlib.Path,
                timeout_sec: int,
                extra_args: list[str] | None = None,
            ) -> tuple[int, str]:
                calls.append(extra_args)
                if not extra_args:
                    return 1, "Cannot archive the sub-graph ... size 262160, skipped."
                out_aot.write_bytes(b"LIVE_NOLINK")
                return 0, "ok"

            with mock.patch.object(LAUNCHER, "run_aot_create", side_effect=fake_create):
                outcome = LAUNCHER.finalize_recorded_aot_config(
                    java_bin="java",
                    jvm_args=["-Xms2G"],
                    classpath="/cp/a.jar",
                    cfg_path=cfg_path,
                    tmp_aot=tmp_aot,
                    timeout_sec=120,
                    mode_label="live",
                )

            self.assertTrue(outcome.ok)
            self.assertEqual(outcome.mode, "live-no-class-linking")
            self.assertFalse(outcome.aot_class_linking)
            self.assertEqual(calls, [None, ["-XX:-AOTClassLinking"]])

    def test_train_only_returns_nonzero_when_aot_creation_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            jar = root / "bundler.jar"
            _create_dummy_bundler(jar)
            wd = root / "wd"
            wd.mkdir()

            failed_outcome = LAUNCHER.TrainingOutcome(
                ok=False, mode="auto", aot_class_linking=True, detail="synthetic failure"
            )
            with (
                mock.patch.object(LAUNCHER, "detect_java_version", return_value=(25, 'openjdk version "25.0.4.1"')),
                mock.patch.object(LAUNCHER, "ensure_unpacked", return_value=[wd / "versions" / "a.jar"]),
                mock.patch.object(LAUNCHER, "train_aot_cache", return_value=failed_outcome),
            ):
                rc = LAUNCHER.main(["--jar", str(jar), "--workdir", str(wd), "--train-only"])

            self.assertNotEqual(rc, 0)

    def test_user_feedback_logged_for_cached_boot_and_assembly_phases(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            jar = root / "bundler.jar"
            _create_dummy_bundler(jar)
            wd = root / "wd"
            wd.mkdir()

            # 1) Cached launch prints confirmation banner to stderr
            sha = LAUNCHER.sha256_file(jar)
            key = LAUNCHER.compute_cache_key(sha, 'openjdk version "25.0.4.1"', [], "org.bukkit.craftbukkit.Main")
            aot_dir = wd / ".goldleaf-aot"
            aot_dir.mkdir()
            (aot_dir / f"goldleaf-{key}.aot").write_bytes(b"X" * 1024)
            (aot_dir / f"goldleaf-{key}.meta.json").write_text(
                json.dumps({"cache_key": key, "train_mode": "warm"}), encoding="utf-8"
            )

            stderr_buf = io.StringIO()
            fake_proc = mock.Mock(returncode=0)
            with (
                mock.patch.object(LAUNCHER, "detect_java_version", return_value=(25, 'openjdk version "25.0.4.1"')),
                mock.patch.object(LAUNCHER, "ensure_unpacked", return_value=[wd / "versions" / "a.jar"]),
                mock.patch.object(LAUNCHER.subprocess, "run", return_value=fake_proc),
                contextlib.redirect_stderr(stderr_buf),
            ):
                rc = LAUNCHER.main(["--jar", str(jar), "--workdir", str(wd)])

            self.assertEqual(rc, 0)
            self.assertIn("Using cached Leyden AOTCache", stderr_buf.getvalue())
            self.assertIn("mode=warm", stderr_buf.getvalue())

            # 2) Live-run cold boot prints upfront explanation + post-/stop assembly banner
            (aot_dir / f"goldleaf-{key}.aot").unlink()
            (aot_dir / f"goldleaf-{key}.meta.json").unlink()

            def fake_live_run(cmd: list[str], cwd: str, check: bool = False) -> mock.Mock:
                for arg in cmd:
                    if arg.startswith("-XX:AOTConfiguration="):
                        pathlib.Path(arg.split("=", 1)[1]).write_bytes(b"CFG")
                return mock.Mock(returncode=0)

            def fake_create(
                java_bin: str,
                jvm_args: list[str],
                classpath: str,
                cfg: pathlib.Path,
                out_aot: pathlib.Path,
                timeout_sec: int,
                extra_args: list[str] | None = None,
            ) -> tuple[int, str]:
                out_aot.write_bytes(b"AOTDATA")
                return 0, "ok"

            stderr_live = io.StringIO()
            with (
                mock.patch.object(LAUNCHER, "detect_java_version", return_value=(25, 'openjdk version "25.0.4.1"')),
                mock.patch.object(LAUNCHER, "ensure_unpacked", return_value=[wd / "versions" / "a.jar"]),
                mock.patch.object(LAUNCHER.subprocess, "run", side_effect=fake_live_run),
                mock.patch.object(LAUNCHER, "run_aot_create", side_effect=fake_create),
                contextlib.redirect_stderr(stderr_live),
            ):
                rc_live = LAUNCHER.main(["--jar", str(jar), "--workdir", str(wd)])

            self.assertEqual(rc_live, 0)
            live_err = stderr_live.getvalue()
            self.assertIn("First run for this JAR/JVM/flag set", live_err)
            self.assertIn("Assembling Leyden AOTCache archive", live_err)
            self.assertIn("Promoted AOTCache", live_err)


if __name__ == "__main__":
    unittest.main()
