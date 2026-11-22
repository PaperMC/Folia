#!/usr/bin/env python3
"""Unit tests for GoldLeaf/scripts/goldleaf-launch.py (goldleaf-ADR-016 Layer A)."""

from __future__ import annotations

import importlib.util
import json
import pathlib
import tempfile
import unittest
import zipfile

MODULE_PATH = pathlib.Path(__file__).with_name("goldleaf-launch.py")
SPEC = importlib.util.spec_from_file_location("goldleaf_launch", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
LAUNCHER = importlib.util.module_from_spec(SPEC)
import sys
sys.modules[SPEC.name] = LAUNCHER
SPEC.loader.exec_module(LAUNCHER)


class GoldLeafLauncherTest(unittest.TestCase):
    def test_parse_bundler_manifest_preserves_exact_paperclip_order(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            jar = pathlib.Path(tmp) / "bundler.jar"
            with zipfile.ZipFile(jar, "w") as zf:
                zf.writestr("META-INF/main-class", "org.bukkit.craftbukkit.Main\n")
                zf.writestr("META-INF/versions.list", "abc\t26.3\t26.3/goldleaf-26.3.jar\n")
                zf.writestr(
                    "META-INF/libraries.list",
                    "h1\ta:b:1\ta/b/1/b-1.jar\nh2\tc:d:2\tc/d/2/d-2.jar\n",
                )

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
            "shaA", "openjdk 25", ["-Xms2G", "-XX:AOTCache=old.aot", "-Xmx2G"], "Main"
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


if __name__ == "__main__":
    unittest.main()
