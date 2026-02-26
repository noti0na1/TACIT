"""Task runner for SafeExecMCP benchmark."""

from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path

from agentdojo.agent_pipeline.agent_pipeline import AgentPipeline
from agentdojo.agent_pipeline.errors import AbortAgentError
from agentdojo.functions_runtime import FunctionCall, FunctionsRuntime
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
    classified: bool = True,
    record_dir: Path | None = None,
    llm_config: dict | None = None,
) -> dict:
    """Run a single user task with full MCP server lifecycle management.

    Args:
        pipeline: The agent pipeline.
        user_task_id: e.g. ``"user_task_0"``.
        injection_task_id: Optional injection task for security testing.
        attack_payload: Optional custom injection payload (set on all vectors).
        mcp_command: Command to start the MCP server (e.g. ["java", "-jar", "..."]).
        verbose: Print debug info.
        classified: If True (default), pass --classified-paths to protect secrets.
        record_dir: Directory for MCP execution logs.
        llm_config: Optional LLM config dict to pass to the MCP server.

    Returns:
        Dict with keys: ``user_task_id``, ``utility``, ``security``,
        ``model_output``, ``injection_task_id``, ``messages``, ``error``,
        ``classified``.
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
    env.classified = classified

    # Start MCP server (copies template, applies injections to files on disk)
    tmp_dir = setup_run(env, mcp_command=mcp_command, classified=classified, record_dir=record_dir, llm_config=llm_config)
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

        # Also check user task's own security method (for malicious tasks)
        if security and hasattr(user_task, 'security'):
            security = user_task.security(model_output, pre_env, post_env)

        return {
            "user_task_id": user_task_id,
            "injection_task_id": injection_task_id,
            "utility": utility,
            "security": security,
            "model_output": model_output,
            "messages": list(messages),
            "error": None,
            "classified": classified,
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
            "classified": classified,
        }

    finally:
        teardown_run(tmp_dir)


def run_ground_truth(
    user_task_id: str,
    mcp_command: list[str] | None = None,
    classified: bool = True,
    record_dir: Path | None = None,
    llm_config: dict | None = None,
) -> dict:
    """Execute a task's ground truth code and verify the result.

    This bypasses the LLM entirely — it runs the predefined ``ground_truth()``
    function calls directly against the MCP server, then checks:

    - **Regular tasks**: ``utility(GROUND_TRUTH_OUTPUT)`` should pass.
    - **Malicious tasks**: ``security()`` is checked against the **actual REPL
      output**.  In classified mode the ``Classified[T]`` wrapper redacts
      secrets so security should *pass*; in unclassified mode the raw secrets
      leak so security should *fail*.

    Returns:
        Dict with keys: ``user_task_id``, ``classified``, ``utility``,
        ``security`` (malicious only), ``expected_security`` (malicious only),
        ``ground_truth_output``, ``tool_outputs``, ``error``.
    """
    suite = task_suite
    user_task = suite.get_user_task_by_id(user_task_id)
    is_malicious = getattr(user_task, "IS_MALICIOUS", False)

    # No injections — ground truth runs against clean template data
    injections = dict(suite.get_injection_vector_defaults())
    env = suite.load_and_inject_default_environment(injections)
    env.classified = classified

    tmp_dir = setup_run(
        env,
        mcp_command=mcp_command,
        classified=classified,
        record_dir=record_dir,
        llm_config=llm_config,
    )
    try:
        pre_env = env.model_copy(deep=True)
        runtime = FunctionsRuntime(suite.tools)

        # Execute ground truth function calls
        gt_calls: list[FunctionCall] = user_task.ground_truth(pre_env)
        tool_outputs: list[str] = []
        for call in gt_calls:
            result, error = runtime.run_function(env, call.function, call.args)
            if error:
                tool_outputs.append(f"ERROR: {error}")
            else:
                tool_outputs.append(str(result))

        if is_malicious:
            # Use actual REPL output as proxy for model output.
            # Classified mode → secrets are redacted → security passes.
            # Unclassified mode → raw secrets returned → security fails.
            combined_output = "\n".join(tool_outputs)
            security = user_task.security(combined_output, pre_env, env)
            return {
                "user_task_id": user_task_id,
                "classified": classified,
                "utility": True,
                "security": security,
                "expected_security": classified,
                "ground_truth_output": combined_output,
                "tool_outputs": tool_outputs,
                "error": None,
            }
        else:
            # Check utility against the expected output
            gt_output = getattr(user_task, "GROUND_TRUTH_OUTPUT", "")
            utility = user_task.utility(gt_output, pre_env, env)
            return {
                "user_task_id": user_task_id,
                "classified": classified,
                "utility": utility,
                "ground_truth_output": gt_output,
                "tool_outputs": tool_outputs,
                "error": None,
            }

    except Exception as exc:
        base = {
            "user_task_id": user_task_id,
            "classified": classified,
            "utility": False,
            "ground_truth_output": "",
            "tool_outputs": [],
            "error": str(exc),
        }
        if is_malicious:
            base["security"] = None
            base["expected_security"] = classified
        return base

    finally:
        teardown_run(tmp_dir)


def run_injection_ground_truth(
    injection_task_id: str,
    mcp_command: list[str] | None = None,
    classified: bool = True,
    record_dir: Path | None = None,
    llm_config: dict | None = None,
) -> dict:
    """Execute an injection task's ground truth code and verify security.

    Starts an MCP server, runs the predefined ``ground_truth()`` FunctionCalls,
    and checks ``security()`` against the actual REPL output.

    - **classified=True** → Classified[T] redacts secrets → security should *pass*.
    - **classified=False** → raw secrets leak → security should *fail*.

    Returns:
        Dict with keys: ``injection_task_id``, ``classified``, ``security``,
        ``expected_security``, ``ground_truth_output``, ``tool_outputs``,
        ``error``.
    """
    suite = task_suite
    injection_task = suite.get_injection_task_by_id(injection_task_id)

    # No injections — ground truth runs against clean template data
    injections = dict(suite.get_injection_vector_defaults())
    env = suite.load_and_inject_default_environment(injections)
    env.classified = classified

    tmp_dir = setup_run(
        env,
        mcp_command=mcp_command,
        classified=classified,
        record_dir=record_dir,
        llm_config=llm_config,
    )
    try:
        pre_env = env.model_copy(deep=True)
        runtime = FunctionsRuntime(suite.tools)

        # Execute ground truth function calls
        gt_calls: list[FunctionCall] = injection_task.ground_truth(pre_env)
        tool_outputs: list[str] = []
        for call in gt_calls:
            result, error = runtime.run_function(env, call.function, call.args)
            if error:
                tool_outputs.append(f"ERROR: {error}")
            else:
                tool_outputs.append(str(result))

        # Use actual REPL output for security check.
        # Classified mode → secrets redacted → security passes.
        # Unclassified mode → raw secrets leak → security fails.
        combined_output = "\n".join(tool_outputs)
        security = injection_task.security(combined_output, pre_env, env)
        return {
            "injection_task_id": injection_task_id,
            "classified": classified,
            "security": security,
            "expected_security": classified,
            "ground_truth_output": combined_output,
            "tool_outputs": tool_outputs,
            "error": None,
        }

    except Exception as exc:
        return {
            "injection_task_id": injection_task_id,
            "classified": classified,
            "security": None,
            "expected_security": classified,
            "ground_truth_output": "",
            "tool_outputs": [],
            "error": str(exc),
        }

    finally:
        teardown_run(tmp_dir)
