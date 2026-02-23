#!/usr/bin/env python3
"""
SWE-bench harness for opencode + SafeExecMCP.

Runs opencode on each SWE-bench instance, captures the resulting diff,
and writes predictions in standard SWE-bench JSONL format.

Usage:
    python run_swebench.py                                  # Run all 300 instances
    python run_swebench.py --instance django__django-11039  # Single instance
    python run_swebench.py --first 10                       # First N instances
    python run_swebench.py --resume                         # Skip completed instances
    python run_swebench.py --timeout 900                    # 15-minute timeout
    python run_swebench.py -j 4                             # Run 4 instances in parallel
    python run_swebench.py --mode mcp_only                  # Use MCP only, no built-in tools
"""

import argparse
import json
import os
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

from datasets import load_dataset

# Defaults
DATASET = "princeton-nlp/SWE-bench_Lite"
# DEFAULT_MODEL = "openrouter/anthropic/claude-sonnet-4.5"
DEFAULT_MODEL = "openrouter/minimax/minimax-m2.5"
DEFAULT_TIMEOUT = 900  # 15 minutes
# MODEL_NAME = "opencode-claude-sonnet-4.5"
MODEL_NAME = "opencode-minimax-m2.5"
OPENCODE_BIN = os.environ.get(
    "OPENCODE_BIN",
    os.path.expanduser("~/.opencode/bin/opencode"),
)
def _find_safexec_jar() -> str:
    """Find the SafeExecMCP assembly JAR under ../target/scala-*/."""
    project_root = Path(__file__).resolve().parent.parent
    jars = sorted(project_root.glob("target/scala-*/SafeExecMCP-assembly-*.jar"))
    if jars:
        return str(jars[-1])
    return str(project_root / "target" / "SafeExecMCP-assembly.jar")  # placeholder
SAFEXEC_JAR = os.environ.get("SAFEXEC_JAR", _find_safexec_jar())
MCP_CONFIG = os.environ.get(
    "MCP_CONFIG",
    str(Path(__file__).resolve().parent / "mcp_config.json"),
)


def load_completed(predictions_path: Path) -> set[str]:
    """Load already-completed instance IDs from predictions JSONL."""
    completed = set()
    if predictions_path.exists():
        for line in predictions_path.read_text().splitlines():
            line = line.strip()
            if line:
                try:
                    completed.add(json.loads(line)["instance_id"])
                except (json.JSONDecodeError, KeyError):
                    pass
    return completed


def build_opencode_config(mode: str) -> dict:
    """Build opencode.json config dict for the given mode."""
    mcp_block = {
        "scala-exec": {
            "type": "local",
            "enabled": True,
            "command": [
                "java",
                "-jar",
                SAFEXEC_JAR,
                "--config",
                MCP_CONFIG,
            ],
        }
    }

    if mode == "mcp_only":
        # Deny all built-in tools, only allow MCP
        return {
            "$schema": "https://opencode.ai/config.json",
            "permission": {
                "*": "deny",
                "read": "deny",
                "edit": "deny",
                "bash": "deny",
                "glob": "deny",
                "grep": "deny",
                "list": "deny",
                "lsp": "deny",
                "skill": "deny",
                "todoread": "deny",
                "todowrite": "deny",
                "webfetch": "deny",
                "websearch": "deny",
                "codesearch": "deny",
                "task": "deny",
                "external_directory": "deny",
                "scala-exec*": "allow",
            },
            "mcp": mcp_block,
        }
    else:
        # Default: all tools allowed, no MCP
        return {
            "$schema": "https://opencode.ai/config.json",
            "permission": "allow",
        }


def write_opencode_config(repo_dir: Path, mode: str) -> None:
    """Write opencode.json into the repo directory."""
    config = build_opencode_config(mode)
    (repo_dir / "opencode.json").write_text(json.dumps(config, indent=2) + "\n")


def clone_and_checkout(repo: str, base_commit: str, repo_dir: Path) -> bool:
    """Clone a GitHub repo and checkout the base commit. Returns True on success."""
    if repo_dir.exists():
        # Already cloned — just make sure we're on the right commit
        result = subprocess.run(
            ["git", "checkout", "-f", base_commit],
            cwd=repo_dir,
            capture_output=True,
            text=True,
        )
        if result.returncode == 0:
            # Clean any leftover changes
            subprocess.run(
                ["git", "clean", "-fdx"],
                cwd=repo_dir,
                capture_output=True,
            )
            return True
        # If checkout failed, remove and re-clone
        subprocess.run(["rm", "-rf", str(repo_dir)])

    repo_url = f"https://github.com/{repo}.git"
    print(f"  Cloning {repo_url} ...")
    result = subprocess.run(
        ["git", "clone", "--quiet", repo_url, str(repo_dir)],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(f"  ERROR cloning: {result.stderr.strip()}")
        return False

    result = subprocess.run(
        ["git", "checkout", "-f", base_commit],
        cwd=repo_dir,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(f"  ERROR checking out {base_commit}: {result.stderr.strip()}")
        return False

    return True


def get_diff(repo_dir: Path) -> str:
    """Get the git diff of all changes in the repo, excluding opencode.json."""
    # Stage everything so we capture new files too
    subprocess.run(
        ["git", "add", "-A"],
        cwd=repo_dir,
        capture_output=True,
    )
    result = subprocess.run(
        ["git", "diff", "--cached", "--", ".", ":!opencode.json"],
        cwd=repo_dir,
        capture_output=True,
        text=True,
    )
    return result.stdout


def run_opencode(
    repo_dir: Path,
    problem_statement: str,
    log_path: Path,
    model: str,
    timeout: int,
    mode: str,
) -> bool:
    """Run opencode on the problem. Returns True if it completed (even if no diff)."""
    if mode == "mcp_only":
        prompt = (
            "You are a software engineer fixing a bug in a repository. "
            "You have access to an MCP tool called 'scala-exec' that provides a sandboxed "
            "Scala REPL. You MUST use this MCP tool for ALL operations: reading files, "
            "searching code, exploring the repository, and making edits. You do NOT have "
            "access to any built-in tools (no bash, no edit, no read, no grep, no glob, etc.). "
            "The only way to interact with the codebase is through the scala-exec MCP tool.\n\n"
            "Use 'show_interface' to see the full API, then use 'create_repl_session' to start "
            "a session and 'execute_in_session' for all subsequent operations. The API provides "
            "file system access (read, write, list, grep, find), process execution, and network "
            "capabilities that you can request through the capability system.\n\n"
            "PROBLEM STATEMENT:\n"
            f"{problem_statement}\n\n"
            "INSTRUCTIONS:\n"
            "- Use ONLY the scala-exec MCP tool for all operations.\n"
            "- Find and fix the bug described above.\n"
            "- Make only the minimal changes necessary.\n"
            "- Do NOT modify any test files.\n"
            "- Do NOT create new test files.\n"
            "- When you are done, simply stop. The diff will be captured automatically."
        )
    else:
        prompt = (
            "You are a software engineer fixing a bug in a repository. "
            "Read the problem statement below carefully, explore the codebase to "
            "understand the issue, then make the minimal code changes needed to fix it.\n\n"
            "PROBLEM STATEMENT:\n"
            f"{problem_statement}\n\n"
            "INSTRUCTIONS:\n"
            "- Find and fix the bug described above.\n"
            "- Make only the minimal changes necessary.\n"
            "- Do NOT modify any test files.\n"
            "- Do NOT create new test files.\n"
            "- When you are done, simply stop. The diff will be captured automatically."
        )

    cmd = [
        OPENCODE_BIN,
        "run",
        "--model",
        model,
        "--dir",
        str(repo_dir),
        "--format",
        "json",
        prompt,
    ]

    try:
        with open(log_path, "w") as log_file:
            result = subprocess.run(
                cmd,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                timeout=timeout,
                cwd=repo_dir,
            )
        print(f"  opencode result: ${result}")
        return result.returncode == 0
    except subprocess.TimeoutExpired:
        print(f"  TIMEOUT after {timeout}s")
        return False
    except Exception as e:
        print(f"  ERROR running opencode: {e}")
        return False


def run_instance(
    instance: dict,
    workspace_dir: Path,
    predictions_path: Path,
    model: str,
    timeout: int,
    mode: str,
) -> bool:
    """Run a single SWE-bench instance. Returns True if a prediction was written."""
    instance_id = instance["instance_id"]
    repo = instance["repo"]
    base_commit = instance["base_commit"]
    problem_statement = instance["problem_statement"]

    instance_dir = workspace_dir / instance_id
    instance_dir.mkdir(parents=True, exist_ok=True)
    repo_dir = instance_dir / "repo"
    log_path = instance_dir / "opencode.log"

    # 1. Clone and checkout
    if not clone_and_checkout(repo, base_commit, repo_dir):
        return False

    # 2. Write opencode config
    write_opencode_config(repo_dir, mode)

    # 3. Run opencode
    print(f"  Running opencode (timeout={timeout}s) ...")
    success = run_opencode(repo_dir, problem_statement, log_path, model, timeout, mode)
    if not success:
        print(f"  opencode exited with error (see {log_path})")
        # Still try to capture any partial diff

    # 4. Capture diff
    patch = get_diff(repo_dir)
    if not patch.strip():
        print(f"  WARNING: empty diff")

    # 5. Write prediction (thread-safe via lock)
    prediction = {
        "instance_id": instance_id,
        "model_name_or_path": MODEL_NAME,
        "model_patch": patch,
    }
    with _predictions_lock:
        with open(predictions_path, "a") as f:
            f.write(json.dumps(prediction) + "\n")

    print(f"  [{instance_id}] Prediction written ({len(patch)} bytes patch)")
    return True


_predictions_lock = threading.Lock()


def main():
    parser = argparse.ArgumentParser(
        description="Run opencode on SWE-bench Lite instances"
    )
    parser.add_argument(
        "--instance",
        type=str,
        help="Run a single instance by ID (e.g. django__django-11039)",
    )
    parser.add_argument(
        "--first",
        type=int,
        help="Run only the first N instances",
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Skip already-completed instances in the output file",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=DEFAULT_TIMEOUT,
        help=f"Timeout per instance in seconds (default: {DEFAULT_TIMEOUT})",
    )
    parser.add_argument(
        "--model",
        type=str,
        default=DEFAULT_MODEL,
        help=f"Model to use (default: {DEFAULT_MODEL})",
    )
    parser.add_argument(
        "--mode",
        type=str,
        choices=["default", "mcp_only"],
        default="default",
        help="Tool mode: 'default' (built-in tools) or 'mcp_only' (MCP only, all built-in tools denied)",
    )
    parser.add_argument(
        "-j",
        "--parallel",
        type=int,
        default=1,
        help="Number of instances to run in parallel (default: 1)",
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        help="Output directory (default: bench/swebench_runs/<timestamp>)",
    )
    args = parser.parse_args()

    # Setup output directory
    bench_dir = Path(__file__).resolve().parent
    if args.output_dir:
        run_dir = Path(args.output_dir)
    elif args.resume:
        # Find the most recent existing run directory
        runs_root = bench_dir / "swebench_runs"
        existing = sorted(runs_root.iterdir()) if runs_root.is_dir() else []
        existing = [d for d in existing if d.is_dir()]
        if existing:
            run_dir = existing[-1]
            print(f"Resuming from latest run: {run_dir}")
        else:
            print("ERROR: --resume but no previous runs found in swebench_runs/")
            sys.exit(1)
    else:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        run_dir = bench_dir / "swebench_runs" / timestamp
    run_dir.mkdir(parents=True, exist_ok=True)
    workspace_dir = run_dir / "workspace"
    workspace_dir.mkdir(exist_ok=True)
    predictions_path = run_dir / "predictions.jsonl"

    print(f"Output directory: {run_dir}")
    print(f"Predictions file: {predictions_path}")
    print(f"Mode: {args.mode}")
    print(f"Model: {args.model}")
    print(f"Timeout: {args.timeout}s")
    print(f"SafeExecMCP JAR: {SAFEXEC_JAR}")
    print(f"MCP config: {MCP_CONFIG}")
    print()

    # Check prerequisites
    if not Path(OPENCODE_BIN).exists():
        print(f"ERROR: opencode not found at {OPENCODE_BIN}")
        print("Set OPENCODE_BIN env var or install opencode.")
        sys.exit(1)

    if not Path(SAFEXEC_JAR).exists():
        print(f"WARNING: SafeExecMCP JAR not found at {SAFEXEC_JAR}")
        print("MCP tools will not be available. Run `sbt assembly` to build.")
        print()

    # Load dataset
    print("Loading SWE-bench dataset ...")
    dataset = load_dataset(DATASET, split="test")
    print(f"Loaded {len(dataset)} instances")

    # Filter instances
    if args.instance:
        instances = [d for d in dataset if d["instance_id"] == args.instance]
        if not instances:
            print(f"ERROR: instance '{args.instance}' not found in dataset")
            sys.exit(1)
    elif args.first:
        instances = list(dataset.select(range(min(args.first, len(dataset)))))
    else:
        instances = list(dataset)

    # Resume support
    completed = set()
    if args.resume:
        completed = load_completed(predictions_path)
        if completed:
            print(f"Resuming: {len(completed)} instances already completed")

    # Filter out completed
    instances = [i for i in instances if i["instance_id"] not in completed]
    print(f"Running {len(instances)} instances\n")

    # Run
    succeeded = 0
    failed = 0
    start_time = time.time()
    num_workers = max(1, args.parallel)

    if num_workers == 1:
        # Sequential mode (original behavior)
        for idx, instance in enumerate(instances):
            instance_id = instance["instance_id"]
            print(f"[{idx + 1}/{len(instances)}] {instance_id}")

            try:
                ok = run_instance(
                    instance, workspace_dir, predictions_path, args.model, args.timeout, args.mode
                )
                if ok:
                    succeeded += 1
                else:
                    failed += 1
            except KeyboardInterrupt:
                print("\nInterrupted by user")
                break
            except Exception as e:
                print(f"  UNEXPECTED ERROR: {e}")
                failed += 1

            print()
    else:
        # Parallel mode
        print(f"Running with {num_workers} parallel workers\n")

        def _run_one(idx_instance):
            idx, instance = idx_instance
            instance_id = instance["instance_id"]
            print(f"[{idx + 1}/{len(instances)}] START {instance_id}")
            try:
                ok = run_instance(
                    instance, workspace_dir, predictions_path, args.model, args.timeout, args.mode
                )
                return instance_id, ok, None
            except Exception as e:
                return instance_id, False, e

        with ThreadPoolExecutor(max_workers=num_workers) as executor:
            futures = {
                executor.submit(_run_one, (idx, inst)): inst["instance_id"]
                for idx, inst in enumerate(instances)
            }
            try:
                for future in as_completed(futures):
                    instance_id, ok, err = future.result()
                    if err:
                        print(f"  [{instance_id}] UNEXPECTED ERROR: {err}")
                        failed += 1
                    elif ok:
                        succeeded += 1
                    else:
                        failed += 1
            except KeyboardInterrupt:
                print("\nInterrupted by user — cancelling pending tasks ...")
                executor.shutdown(wait=False, cancel_futures=True)

    elapsed = time.time() - start_time
    total = succeeded + failed
    print(f"Done: {succeeded}/{total} succeeded, {failed} failed")
    print(f"Elapsed: {elapsed:.0f}s ({elapsed / max(total, 1):.0f}s avg)")
    print(f"Predictions: {predictions_path}")


if __name__ == "__main__":
    main()
