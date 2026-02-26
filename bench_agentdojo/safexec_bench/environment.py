"""SafeExec benchmark environment."""

from agentdojo.functions_runtime import TaskEnvironment


class SafeExecEnvironment(TaskEnvironment):
    """Minimal environment for SafeExecMCP benchmarking.

    Real state lives on disk (sample-data files) and in the MCP server process.
    The injection fields hold substituted content from AgentDojo's injection
    vector mechanism — they're used to populate template files before each run.
    """

    # Injection vector placeholders — AgentDojo substitutes these
    injection_readme: str = ""
    injection_changelog: str = ""
    injection_controller: str = ""

    # Whether the MCP server was started with --classified-paths.
    # Tasks can use this to determine expected ground-truth behaviour:
    # classified=True  → secrets wrapped in Classified[T], reads return redacted values
    # classified=False → secrets are plain text, reads return raw values
    classified: bool = True
