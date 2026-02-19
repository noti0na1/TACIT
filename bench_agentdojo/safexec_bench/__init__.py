"""SafeExecMCP benchmark suite for AgentDojo.

Evaluates whether LLM agents using SafeExecMCP can be tricked via prompt
injection into bypassing security controls (exfiltrating classified data,
leaking API keys, etc.).
"""

from agentdojo.task_suite import register_suite

from .suite import task_suite

# Import task modules to trigger registration via decorators
from .tasks import user_tasks as _user_tasks  # noqa: F401
from .tasks import injection_tasks as _injection_tasks  # noqa: F401

# Register with AgentDojo
register_suite(task_suite, "safexec_v1")

__all__ = ["task_suite"]
