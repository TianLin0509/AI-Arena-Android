"""Behavioral project-prep gates, exercised only in disposable Git fixtures."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


class WorkflowGate(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ai-arena-workflow-")
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / "repo"
        self.repo.mkdir()
        self.env = {**os.environ, "PYTHONIOENCODING": "utf-8", "PYTHONUTF8": "1",
                    "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
                    "GIT_AUTHOR_NAME": "Workflow fixture", "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
                    "GIT_COMMITTER_NAME": "Workflow fixture", "GIT_COMMITTER_EMAIL": "fixture@example.invalid"}
        for name in ("PROJECT_PREP_ALLOW_COMMIT", "PROJECT_PREP_ALLOW_PUSH"):
            self.env.pop(name, None)
        for rel in ("scripts/merge_task.py", ".githooks/pre-commit", ".githooks/pre-push"):
            target = self.repo / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / rel, target)
            target.chmod(0o755)
        (self.repo / ".agents").mkdir()
        (self.repo / ".agents/project.json").write_text(json.dumps({
            "trunk": "main", "test": [[sys.executable, "check.py"]], "afterMerge": []}), encoding="utf-8")
        (self.repo / "check.py").write_text("pass\n", encoding="utf-8")
        (self.repo / "content.txt").write_text("baseline\n", encoding="utf-8")
        self.git("init", "-b", "main")
        self.git("add", ".")
        self.git("commit", "-m", "fixture baseline")
        self.base = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("checkout", "-b", "task")
        (self.repo / "content.txt").write_text("candidate\n", encoding="utf-8")
        self.git("add", "content.txt")
        self.git("commit", "-m", "fixture candidate")
        self.head = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("checkout", "main")
        self.git("config", "core.hooksPath", ".githooks")

    def run_cmd(self, args, cwd=None, check=True, input=None):
        result = subprocess.run(args, cwd=cwd or self.repo, env=self.env, input=input,
                                capture_output=True, text=True, encoding="utf-8", timeout=60)
        if check:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def git(self, *args, **kwargs):
        return self.run_cmd(["git", *args], **kwargs)

    def gate(self, *extra, cwd=None):
        return self.run_cmd([sys.executable, "scripts/merge_task.py", "task",
                             "--expected-head", self.head, "--expected-trunk", self.base, *extra],
                            cwd=cwd, check=False)

    def clean_base(self):
        self.assertEqual(self.git("rev-parse", "HEAD").stdout.strip(), self.base)
        self.assertEqual(self.git("status", "--porcelain").stdout, "")

    def test_dry_run_restores_exact_trunk(self):
        result = self.gate("--dry-run")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.clean_base()

    def test_stale_candidate_and_trunk_refused(self):
        for flag in ("--expected-head", "--expected-trunk"):
            self.assertEqual(self.gate(flag, "0" * 40).returncode, 2)
            self.clean_base()

    def test_failed_validation_returns_nonzero_and_restores(self):
        self.git("checkout", "task")
        (self.repo / "check.py").write_text("raise SystemExit(7)\n", encoding="utf-8")
        self.git("add", "check.py")
        self.git("-c", "core.hooksPath=", "commit", "-m", "fixture deliberate failure")
        self.head = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("checkout", "main")
        result = self.gate("--dry-run")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.clean_base()

    def test_hooks_and_worktree_merge_boundary(self):
        git_bin = Path(shutil.which("git")).resolve()
        shell = shutil.which("sh") or str(git_bin.parents[1] / "bin/sh.exe")
        self.assertEqual(self.run_cmd([shell, ".githooks/pre-commit"], check=False).returncode, 1)
        wt = Path(self.temp.name) / "author"
        self.git("worktree", "add", str(wt), "task")
        self.assertEqual(self.run_cmd([shell, ".githooks/pre-commit"], cwd=wt).returncode, 0)
        for branch, expected in (("main", 1), ("task", 0)):
            line = f"refs/heads/{branch} {self.head} refs/heads/{branch} {self.base}\n"
            self.assertEqual(self.run_cmd([shell, ".githooks/pre-push", "origin", "fixture"],
                                         input=line, check=False).returncode, expected)
        self.assertEqual(self.gate("--dry-run", cwd=wt).returncode, 2)
        self.clean_base()

    def test_unrelated_dirty_work_preserved(self):
        path = self.repo / "someone-else.txt"
        path.write_text("preserve", encoding="utf-8")
        self.assertEqual(self.gate("--dry-run").returncode, 2)
        self.assertEqual(path.read_text(encoding="utf-8"), "preserve")


class HostRunner(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("arena_checks", ROOT / "scripts/run_checks.py")
        self.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runner)

    def test_gradle_failure_propagates(self):
        with patch.object(self.runner.subprocess, "run", side_effect=[
                subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 7)]):
            self.assertEqual(self.runner.main(), 7)

    def test_empty_junit_collection_fails(self):
        with tempfile.TemporaryDirectory(prefix="ai-arena-junit-") as directory:
            with self.assertRaises(RuntimeError):
                self.runner.verify_junit(Path(directory))

    def test_missing_test_class_fails(self):
        with tempfile.TemporaryDirectory(prefix="ai-arena-junit-") as directory:
            root = Path(directory)
            source = root / "app/src/test/SampleTest.kt"
            source.parent.mkdir(parents=True)
            source.write_text("package sample\nclass SampleTest { @Test fun checks() {} }", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                self.runner.verify_junit(root)


if __name__ == "__main__":
    unittest.main()
