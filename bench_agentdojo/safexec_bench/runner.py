"""Task runner for SafeExecMCP benchmark."""

from __future__ import annotations

from collections.abc import Sequence

from agentdojo.agent_pipeline.agent_pipeline import AgentPipeline
from agentdojo.agent_pipeline.errors import AbortAgentError
from agentdojo.functions_runtime import FunctionsRuntime
from agentdojo.task_suite.task_suite import (
    functions_stack_trace_from_messages,
    model_output_from_messages,
)
from agentdojo.types import ChatMessage, get_text_content_as_str

from .lifecycle import setup_run, teardown_run
from .suite import task_suite


def run_task(
    pipeline: AgentPipeline,
    user_task_id: str,
    injection_task_id: str | None = None,
    attack_payload: str | None = None,
    mcp_command: list[str] | None = None,
    verbose: bool = False,
) -> dict:
    """Run a single user task with full MCP server lifecycle management.

    Args:
        pipeline: The agent pipeline.
        user_task_id: e.g. ``"user_task_0"``.
        injection_task_id: Optional injection task for security testing.
        attack_payload: Optional custom injection payload (set on all vectors).
        mcp_command: Command to start the MCP server (e.g. ["java", "-jar", "..."]).
        verbose: Print debug info.

    Returns:
        Dict with keys: ``user_task_id``, ``utility``, ``security``,
        ``model_output``, ``injection_task_id``, ``messages``, ``error``.
    """
    suite = task_suite
    user_task = suite.get_user_task_by_id(user_task_id)

    # Resolve injection task (if any)
    injection_task = None
    if injection_task_id:
        injection_task = suite.get_injection_task_by_id(injection_task_id)

    # Build injections dict
    injection_defaults = suite.get_injection_vector_defaults()
    injections = dict(injection_defaults)  # start with defaults (empty strings)

    if attack_payload:
        for key in injections:
            injections[key] = attack_payload
    elif injection_task:
        for key in injections:
            injections[key] = injection_task.GOAL

    # Load environment with injections applied
    env = suite.load_and_inject_default_environment(injections)

    # Start MCP server (copies template, applies injections to files on disk)
    tmp_dir = setup_run(env, mcp_command=mcp_command)
    try:
        pre_env = env.model_copy(deep=True)
        runtime = FunctionsRuntime(suite.tools)

        # Run the pipeline
        messages: Sequence[ChatMessage] = []
        post_env = env
        try:
            _, _, post_env, messages, _ = pipeline.query(
                user_task.PROMPT, runtime, env
            )
        except AbortAgentError as exc:
            post_env = exc.task_environment  # type: ignore[assignment]
            messages = exc.messages

        # Extract model output
        model_output_blocks = None
        try:
            model_output_blocks = model_output_from_messages(messages)
        except (ValueError, IndexError):
            pass

        model_output = get_text_content_as_str(model_output_blocks or [])

        # Check utility
        traces = functions_stack_trace_from_messages(messages)
        utility_from_traces = user_task.utility_from_traces(
            model_output, pre_env, post_env, traces
        )
        if utility_from_traces is not None:
            utility = utility_from_traces
        else:
            utility = user_task.utility(model_output, pre_env, post_env)

        # Check security
        security = True
        if injection_task:
            security = injection_task.security(model_output, pre_env, post_env)
        elif attack_payload:
            # No specific injection task — check against ALL injection tasks
            for itask in suite.injection_tasks.values():
                if not itask.security(model_output, pre_env, post_env):
                    security = False
                    break

        return {
            "user_task_id": user_task_id,
            "injection_task_id": injection_task_id,
            "utility": utility,
            "security": security,
            "model_output": model_output,
            "messages": list(messages),
            "error": None,
        }

    except Exception as exc:
        return {
            "user_task_id": user_task_id,
            "injection_task_id": injection_task_id,
            "utility": False,
            "security": True,
            "model_output": "",
            "messages": [],
            "error": str(exc),
        }

    finally:
        teardown_run(tmp_dir)
