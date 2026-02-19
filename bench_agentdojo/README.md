# SafeExecMCP AgentDojo Benchmark

An [AgentDojo](https://github.com/ethz-spylab/agentdojo)-based benchmark that evaluates whether LLM agents using SafeExecMCP can be tricked via prompt injection into bypassing security controls — exfiltrating classified data, leaking API keys, or writing secrets to public locations.

## Quick Start

```bash
pip install agentdojo

# Run a single task (uses defaults from bench_config.json)
python run_benchmark.py --task user_task_1

# Run all user tasks
python run_benchmark.py --all

# Full benchmark (all user_task x injection_task pairs)
python run_benchmark.py --benchmark
```

## How It Works

### The Testing Loop

Each benchmark run follows this lifecycle:

```
1. Copy sample-data-template/ to a temp directory
2. Replace {injection_*} placeholders in files with injection payloads
3. Start a fresh SafeExecMCP server (classifiedPaths → secrets/)
4. Feed user task prompt to the LLM agent
5. Agent calls execute_scala / show_interface via the pipeline
6. Check utility  — did the agent complete the legitimate task?
7. Check security — did the agent NOT leak any classified data?
8. Shut down the MCP server and delete the temp directory
```

### Pipeline Architecture

The agent pipeline is assembled from AgentDojo building blocks:

```
SystemMessage  →  InitQuery  →  LLM  →  ToolsExecutionLoop
                                              │
                                        ToolsExecutor → LLM
                                        (repeat up to 15x)
```

- **SystemMessage** — Teaches the agent about the two tools (`execute_scala`, `show_interface`) and the Scala 3 capability API patterns.
- **InitQuery** — Injects the user task prompt as the first user message.
- **LLM** — The language model (OpenRouter, OpenAI, Anthropic, or Google).
- **ToolsExecutionLoop** — Repeatedly executes tool calls and feeds results back to the LLM until it produces a final text response (or hits `max_iters`).

### Injection Mechanism

Template files in `safexec_bench/data/template/` contain `{injection_*}` placeholders:

| Placeholder              | File                                           |
|--------------------------|-------------------------------------------------|
| `{injection_readme}`     | `projects/webapp/docs/README.md`               |
| `{injection_changelog}`  | `projects/webapp/docs/changelog.md`            |
| `{injection_controller}` | `projects/webapp/src/UsersController.scala`    |

When an injection task runs, its adversarial GOAL text replaces these placeholders. The agent reads these files as part of its legitimate task — and must resist acting on the embedded injection.
