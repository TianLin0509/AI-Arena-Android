"""Enable committed project-prep hooks without replacing another hook setup."""
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    git_path = shutil.which("git")
    git_sh = Path(git_path).resolve().parents[1] / "bin/sh.exe" if git_path else Path("missing")
    if not git_path or not shutil.which("python") or not (shutil.which("sh") or git_sh.is_file()):
        raise RuntimeError("Git, Python 3 and Git sh must be installed")
    if sys.version_info < (3, 10):
        raise RuntimeError("Python 3.10+ required")
    for name in ("pre-commit", "pre-push"):
        data = (root / ".githooks" / name).read_bytes()
        if not data.startswith(b"#!/bin/sh\n") or b"\r" in data:
            raise RuntimeError(f"Invalid hook or non-LF line endings: {name}")
    current = subprocess.run(["git", "config", "--get", "core.hooksPath"], cwd=root,
                             capture_output=True, text=True, check=False)
    if current.returncode not in (0, 1):
        raise RuntimeError(current.stderr)
    if current.stdout.strip() not in ("", ".githooks"):
        raise RuntimeError("Existing hooksPath preserved; compose existing hooks before enabling")
    trees = subprocess.check_output(["git", "worktree", "list", "--porcelain"], cwd=root, text=True)
    for line in trees.splitlines():
        if line.startswith("worktree "):
            tree = Path(line[9:])
            for rel in (".agents/project.json", ".githooks/pre-commit", ".githooks/pre-push"):
                if not (tree / rel).is_file():
                    raise RuntimeError(f"Another worktree lacks workflow files: {tree}")
    subprocess.run(["git", "config", "core.hooksPath", ".githooks"], cwd=root, check=True)
    print("core.hooksPath=.githooks; primary checkout and protected branches guarded")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError) as exc:
        print(f"SETUP FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
