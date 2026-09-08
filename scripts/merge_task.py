#!/usr/bin/env python3
"""Reviewed-SHA local merge gate. Config commands are trusted project code.

Exit 0: verified dry-run / local merge / already merged.
Exit 1: verification failed and the known trial was safely aborted.
Exit 2: precondition failed or unknown state preserved for inspection.
Exit 3: commit exists, but a post-commit check/action failed. Do not retry blindly.
No fetch, push, rebase, branch checkout, or reset is performed.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import stat
import subprocess
import sys
from pathlib import Path, PurePosixPath

REPO = Path(__file__).resolve().parents[1]
LOCK_HANDLE = None
BYPASS_NAMES = ("HUB_ALLOW_MAIN_COMMIT", "HUB_ALLOW_TRUNK_PUSH",
                "PROJECT_PREP_ALLOW_COMMIT", "PROJECT_PREP_ALLOW_PUSH")


def say(message):
    print(message, flush=True)


def run(command, *, check=True, capture=True, env=None):
    child_env = dict(os.environ)
    for name in BYPASS_NAMES:
        child_env.pop(name, None)
    child_env.update(PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    child_env.update(env or {})
    result = subprocess.run(command, cwd=REPO, env=child_env,
                            shell=isinstance(command, str), capture_output=capture,
                            text=True, encoding="utf-8", errors="replace")
    if check and result.returncode:
        detail = ((result.stdout or "") + (result.stderr or "")) if capture else "see output above"
        raise RuntimeError(f"Command failed ({result.returncode}): {command}\n{detail}")
    return result


def git(*args, **kwargs):
    return run(["git", *args], **kwargs).stdout.strip()


def paths(*args):
    return set(filter(None, run(["git", *args, "-z"]).stdout.split("\0")))


def ref(name):
    return git("rev-parse", "--verify", name)


def optional_ref(name):
    result = run(["git", "rev-parse", "--verify", name], check=False)
    return result.stdout.strip() if result.returncode == 0 else None


def acquire_lock():
    global LOCK_HANDLE
    common = Path(git("rev-parse", "--path-format=absolute", "--git-common-dir"))
    handle = open(common / "project-prep-merge.lock", "a+b")
    handle.seek(0, os.SEEK_END)
    if not handle.tell():
        handle.write(b"0")
        handle.flush()
    handle.seek(0)
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        handle.close()
        raise RuntimeError("Another merge gate holds the repository lock; no worktree changes made.")
    LOCK_HANDLE = handle


def commands(value, field, *, required=False):
    def valid(command):
        return ((isinstance(command, str) and bool(command.strip())) or
                (isinstance(command, list) and bool(command) and all(
                    isinstance(arg, str) and bool(arg) for arg in command)))
    if not isinstance(value, list) or (required and not value) or not all(map(valid, value)):
        raise ValueError(f"{field} must be {'a nonempty' if required else 'an'} array of commands.")
    return value


def load_config():
    cfg = json.loads((REPO / ".agents/project.json").read_text(encoding="utf-8-sig"))
    if not isinstance(cfg, dict) or cfg.get("schemaVersion", 1) != 1:
        raise ValueError("Unsupported project configuration/schemaVersion.")
    trunk = cfg.get("trunk")
    if not isinstance(trunk, str) or trunk.startswith("-"):
        raise ValueError("trunk must name an existing local branch.")
    git("check-ref-format", f"refs/heads/{trunk}")
    for field in ("test", "versionBump", "afterMerge"):
        cfg[field] = commands(cfg.get(field, []), field, required=field == "test")
    files = cfg.get("versionFiles", [])
    if not isinstance(files, list) or len(files) != len(set(files)):
        raise ValueError("versionFiles must contain unique relative paths.")
    for item in files:
        if not isinstance(item, str) or not item or "\\" in item or ":" in item:
            raise ValueError("versionFiles must use repository-relative forward-slash paths.")
        parts = PurePosixPath(item)
        if parts.is_absolute() or ".." in parts.parts or parts.parts[0] == ".git":
            raise ValueError("versionFiles must stay within the repository.")
        if not (REPO / item).resolve().is_relative_to(REPO):
            raise ValueError("versionFiles may not resolve outside the repository.")
    if bool(files) != bool(cfg["versionBump"]):
        raise ValueError("Configure versionFiles and versionBump together, or leave both empty.")
    cfg["versionFiles"] = files
    test_env = cfg.get("testEnv", {})
    if not isinstance(test_env, dict) or any(
        not isinstance(k, str) or not k or not isinstance(v, str) or k in BYPASS_NAMES
        for k, v in test_env.items()
    ):
        raise ValueError("testEnv must map environment names to strings (no gate bypass flags).")
    cfg["testEnv"] = {k: v.replace("{repo}", str(REPO)) for k, v in test_env.items()}
    return cfg


def metadata_only(base, candidate, path):
    """Only root package versions, never dependency version lines, are disposable."""
    def read_at(sha):
        mode = git("ls-tree", sha, "--", path).split()[0]
        if mode not in ("100644", "100755"):
            raise ValueError("Version conflict is not a regular file")
        obj = json.loads(git("show", f"{sha}:{path}"))
        if not isinstance(obj, dict):
            raise ValueError("Version metadata must be a JSON object")
        root = obj.get("packages", {}).get("") if isinstance(obj.get("packages", {}), dict) else None
        versions = [obj.pop("version", None)]
        if isinstance(root, dict):
            versions.append(root.pop("version", None))
        return mode, obj, versions
    try:
        old, new = read_at(base), read_at(candidate)
        return old[:2] == new[:2] and old[2] != new[2] and all(
            isinstance(v, str) for v in old[2] + new[2])
    except (ValueError, RuntimeError, IndexError):
        return False


def resolve_version_conflicts(original, candidate, files):
    stuck = paths("diff", "--name-only", "--diff-filter=U")
    if not stuck or not stuck.issubset(set(files)):
        return False
    base = git("merge-base", original, candidate)
    if not all(metadata_only(base, candidate, item) for item in stuck):
        return False
    # Both the trunk content and candidate's non-version content remain intact.
    for item in sorted(stuck):
        git("checkout", "--ours", "--", item)
        git("add", "--", item)
    return not paths("diff", "--name-only", "--diff-filter=U")


def predicted_merge_tree(original, candidate):
    if ref("HEAD") != original:
        raise RuntimeError("HEAD changed before merge prediction.")
    # Keep the ours label identical to git merge. File/directory conflicts can
    # create names such as path~HEAD; a SHA label predicts a different filename.
    result = run(["git", "-c", "rerere.enabled=false", "merge-tree", "--write-tree", "-z",
                  "HEAD", candidate], check=False)
    tree = result.stdout.split("\0", 1)[0].strip()
    if result.returncode not in (0, 1) or not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", tree):
        raise RuntimeError(f"Cannot predict merge paths (Git 2.38+ required); no merge performed.\n{result.stderr}")
    if ref("HEAD") != original:
        raise RuntimeError("HEAD changed during merge prediction.")
    return tree


def reject_existing_untracked_collisions(original, merged_tree):
    """Git --no-overwrite-ignore does not protect every no-ff merge path.

    Inspect incoming names directly instead of enumerating a potentially huge
    ignored dependency tree. Include file/directory and symlink ancestors.
    """
    tracked = paths("ls-tree", "-r", "--name-only", original)
    incoming = paths("ls-tree", "-r", "--name-only", merged_tree) - tracked
    for name in sorted(incoming):
        target = REPO / name
        if os.path.lexists(target):
            raise RuntimeError(f"Incoming path collides with existing content (possibly ignored): {name}; preserved.")
        for parent in target.parents:
            if parent == REPO:
                break
            if not os.path.lexists(parent):
                continue
            relative = parent.relative_to(REPO).as_posix()
            is_link = parent.is_symlink() or bool(getattr(parent.lstat(), "st_file_attributes", 0)
                & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))
            if is_link or (not parent.is_dir() and relative not in tracked):
                raise RuntimeError(f"Incoming path has a blocking/link ancestor: {relative}; preserved.")


def validate_version_files(files):
    for path in files:
        entry = git("ls-files", "--stage", "--", path).split()
        if (not entry or entry[0] not in ("100644", "100755")
                or not (REPO / path).is_file()
                or not (REPO / path).resolve().is_relative_to(REPO)):
            raise RuntimeError("Version files must be tracked regular files inside the repository.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("branch", help="Reviewed local task branch")
    parser.add_argument("--expected-head", required=True, help="Full reviewed candidate SHA")
    parser.add_argument("--expected-trunk", required=True, help="Full reviewed trunk SHA")
    parser.add_argument("--dry-run", action="store_true", help="Trial merge and verify, then safely abort")
    args = parser.parse_args()
    acquire_lock()
    cfg = load_config()
    main_line = git("worktree", "list", "--porcelain").splitlines()[0]
    if not REPO.samefile(Path(main_line.removeprefix("worktree "))):
        raise RuntimeError("Run the gate in the primary worktree, not an Author worktree.")
    trunk = cfg["trunk"]
    if git("symbolic-ref", "--short", "HEAD") != trunk:
        raise RuntimeError(f"Primary worktree must already be on {trunk}; no automatic checkout.")
    if args.branch.startswith("-"):
        raise ValueError("Invalid local branch")
    git("check-ref-format", f"refs/heads/{args.branch}")
    original = ref(f"refs/heads/{trunk}")
    candidate = ref(f"refs/heads/{args.branch}^{{commit}}")
    if original != args.expected_trunk or candidate != args.expected_head:
        raise RuntimeError("Candidate/trunk SHA mismatch. Review the current full SHAs again.")
    options = run(["git", "config", "--get", f"branch.{trunk}.mergeOptions"], check=False)
    if options.returncode not in (0, 1) or options.stdout.strip():
        raise RuntimeError("Per-branch mergeOptions are unsupported; prediction and merge must use the same ort strategy.")
    if git("status", "--porcelain", "--untracked-files=all"):
        raise RuntimeError("Primary worktree/index is dirty; all existing work has been preserved.")
    for marker in ("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD"):
        if optional_ref(marker):
            raise RuntimeError(f"Existing {marker}; finish or inspect that operation first.")
    for marker in ("rebase-apply", "rebase-merge", "sequencer", "MERGE_AUTOSTASH"):
        if Path(git("rev-parse", "--path-format=absolute", "--git-path", marker)).exists():
            raise RuntimeError(f"Existing Git operation: {marker}")
    flags = run(["git", "ls-files", "-v"]).stdout.splitlines()
    if any(line and (line[0] == "S" or line[0].islower()) for line in flags):
        raise RuntimeError("Sparse/skip-worktree or assume-unchanged index flags are unsupported.")
    if run(["git", "merge-base", "--is-ancestor", candidate, original], check=False).returncode == 0:
        say(f"ALREADY_MERGED {candidate} -> {trunk} @ {original}")
        return 0
    reject_existing_untracked_collisions(original, predicted_merge_tree(original, candidate))
    expected_tree = None
    commit_started = False

    def stable(tree, check_branch=True):
        return (tree is not None and ref("HEAD") == original
                and git("symbolic-ref", "--short", "HEAD") == trunk
                and optional_ref("MERGE_HEAD") == candidate
                and (not check_branch or ref(f"refs/heads/{args.branch}") == candidate)
                and git("write-tree") == tree
                and not git("diff", "--name-only")
                and not git("ls-files", "--others", "--exclude-standard"))

    def abort_known_trial():
        if not stable(expected_tree, check_branch=False):
            raise RuntimeError("Unknown changes/conflicts: merge --abort was NOT run; index, files and merge state preserved.")
        git("merge", "--abort")
        if ref("HEAD") != original or optional_ref("MERGE_HEAD") or git("status", "--porcelain"):
            raise RuntimeError("Abort did not restore the original clean state; inspect before retrying.")
        say(f"ROLLED_BACK {original}")

    try:
        say(f"TRIAL {candidate} -> {trunk} @ {original}")
        result = run(["git", "-c", "rerere.enabled=false", "merge", "--strategy=ort", "--no-ff", "--no-commit",
                      "--no-autostash", "--no-overwrite-ignore", candidate], check=False)
        if result.returncode and not resolve_version_conflicts(original, candidate, cfg["versionFiles"]):
            raise RuntimeError(f"Merge failed; conflict state preserved.\n{result.stdout}{result.stderr}")
        expected_tree = git("write-tree")
        if not stable(expected_tree):
            raise RuntimeError("Unexpected trial state before version handling.")
        if cfg["versionBump"]:
            validate_version_files(cfg["versionFiles"])
            for command in cfg["versionBump"]:
                say(f"VERSION {command}")
                run(command, capture=False, env=cfg["testEnv"])
            # Only declared paths may change; never sweep concurrent files into the index.
            changed = paths("diff", "--name-only") | paths("diff", "--name-only", "--cached", expected_tree)
            if not changed.issubset(set(cfg["versionFiles"])) or git("ls-files", "--others", "--exclude-standard"):
                raise RuntimeError("Version command changed undeclared files; all work preserved.")
            if ref("HEAD") != original or optional_ref("MERGE_HEAD") != candidate:
                raise RuntimeError("Version command changed Git state; all work preserved.")
            validate_version_files(cfg["versionFiles"])
            git("add", "--", *cfg["versionFiles"])
            expected_tree = git("write-tree")
            if not stable(expected_tree):
                raise RuntimeError("Unexpected state after version handling.")
        for command in cfg["test"]:
            say(f"VERIFY {command}")
            run(command, capture=False, env=cfg["testEnv"])
            if not stable(expected_tree):
                raise RuntimeError("SHA, index or working files changed during verification; refusing commit.")
        if args.dry_run:
            abort_known_trial()
            say(f"DRY_RUN_PASS candidate={candidate} trunk={original}")
            return 0
        if not stable(expected_tree):
            raise RuntimeError("State changed before commit.")
        commit_started = True
        git("commit", "--no-edit", env={"PROJECT_PREP_ALLOW_COMMIT": "1"})
        merged_sha = ref("HEAD")
        parents = git("rev-list", "--parents", "-n", "1", merged_sha).split()[1:]
        if (parents != [original, candidate] or ref("HEAD^{tree}") != expected_tree
                or git("status", "--porcelain") or optional_ref("MERGE_HEAD")
                or git("symbolic-ref", "--short", "HEAD") != trunk
                or ref(f"refs/heads/{args.branch}") != candidate):
            say(f"POST_COMMIT_MISMATCH {merged_sha}; preserved for inspection, no reset performed.")
            return 3
    except (Exception, KeyboardInterrupt) as error:
        say(f"FAILED {error}")
        if commit_started and ref("HEAD") != original:
            say(f"COMMIT_EXISTS {ref('HEAD')}; inspect before retrying. No rollback performed.")
            return 3
        try:
            abort_known_trial()
        except Exception as rollback_error:
            say(f"PRESERVED {rollback_error}")
            return 2
        return 1
    say(f"LOCAL_MERGE_OK {merged_sha}; remote synchronization is a separate action.")
    # No shell interpolation of branch names. Post-actions get data through env.
    for command in cfg["afterMerge"]:
        try:
            say(f"AFTER_MERGE {command}")
            run(command, capture=False, env={**cfg["testEnv"],
                "PROJECT_PREP_BRANCH": args.branch, "PROJECT_PREP_SHA": merged_sha})
        except (Exception, KeyboardInterrupt) as error:
            say(f"AFTER_MERGE_FAILED {error}; commit {merged_sha} remains, no rollback performed.")
            return 3
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    try:
        code = main()
    except (Exception, KeyboardInterrupt) as error:
        say(f"REFUSED {error}; inspect state before retrying.")
        code = 2
    finally:
        if LOCK_HANDLE is not None:
            LOCK_HANDLE.close()
    sys.exit(code)
