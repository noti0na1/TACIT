"""Setup and teardown for SafeExecMCP benchmark runs."""

import shutil
import tempfile
from pathlib import Path

from .environment import SafeExecEnvironment
from .mcp_client import McpClient
from .suite import set_data_dir, set_mcp_client

# Resolve paths relative to this file
_BENCH_DIR = Path(__file__).resolve().parent.parent
_TEMPLATE_DIR = Path(__file__).resolve().parent / "data" / "template"


def _default_mcp_command() -> list[str]:
    """Auto-discover the assembly JAR and return a default server command."""
    target_dir = _BENCH_DIR.parent / "target"
    if target_dir.exists():
        for jar in sorted(target_dir.rglob("SafeExecMCP-assembly-*.jar"), reverse=True):
            return ["java", "-jar", str(jar)]
    raise FileNotFoundError(
        "Cannot find SafeExecMCP JAR. Run `sbt assembly` or set mcpCommand in bench_config.json."
    )


def _apply_injection(file_path: Path, placeholder: str, value: str) -> None:
    """Replace a placeholder in a file with the injection value."""
    if file_path.exists():
        content = file_path.read_text()
        file_path.write_text(content.replace(placeholder, value))


def setup_run(
    environment: SafeExecEnvironment,
    mcp_command: list[str] | None = None,
) -> Path:
    """Set up a benchmark run: copy template, apply injections, start MCP server.

    Args:
        environment: The environment with injection values.
        mcp_command: Command to start the MCP server (e.g. ["java", "-jar", "..."]).
            Falls back to auto-discovery if not provided.

    Returns the temporary directory (caller should clean up via teardown_run).
    The MCP server is started with CWD = data directory so that relative paths
    like "projects/webapp/docs/README.md" resolve correctly.
    """
    # 1. Copy template to temp dir
    tmp = Path(tempfile.mkdtemp(prefix="safexec_bench_"))
    shutil.copytree(_TEMPLATE_DIR, tmp / "data")
    data_dir = tmp / "data"
    set_data_dir(data_dir)

    # 2. Apply injection vectors to template files
    _apply_injection(
        data_dir / "projects" / "webapp" / "docs" / "README.md",
        "{injection_readme}",
        environment.injection_readme,
    )
    _apply_injection(
        data_dir / "projects" / "webapp" / "docs" / "changelog.md",
        "{injection_changelog}",
        environment.injection_changelog,
    )
    _apply_injection(
        data_dir / "projects" / "webapp" / "src" / "UsersController.scala",
        "{injection_controller}",
        environment.injection_controller,
    )

    # 3. Build the full server command with per-run flags
    cmd = list(mcp_command or _default_mcp_command())
    cmd.extend(["--classified-paths", str((data_dir / "secrets").resolve())])
    log_dir = _BENCH_DIR / "log"
    log_dir.mkdir(exist_ok=True)
    cmd.extend(["--record", str(log_dir)])

    # 4. Start MCP server with CWD = data directory
    client = McpClient(cmd, cwd=data_dir)
    client.start()
    client.initialize()
    set_mcp_client(client)

    return tmp


def teardown_run(tmp_dir: Path | None = None) -> None:
    """Shut down MCP server and clean up temp directory."""
    from .suite import _mcp_client

    if _mcp_client is not None:
        _mcp_client.close()
    set_mcp_client(None)

    if tmp_dir is not None:
        shutil.rmtree(tmp_dir, ignore_errors=True)

    set_data_dir(None)
