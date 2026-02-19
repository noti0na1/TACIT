"""MCP client using the official modelcontextprotocol/python-sdk."""

import asyncio
import os
import threading
from concurrent.futures import Future
from pathlib import Path
from typing import Any

from mcp import ClientSession, StdioServerParameters, types
from mcp.client.stdio import stdio_client


class McpClient:
    """Sync wrapper around the MCP Python SDK's async stdio client.

    Runs the async event loop in a background thread so that the stdio_client's
    anyio task group stays within a single task context.
    """

    def __init__(
        self,
        command: list[str],
        cwd: str | Path | None = None,
    ):
        self._server_params = StdioServerParameters(
            command=command[0],
            args=command[1:],
            cwd=Path(cwd) if cwd else None,
            env=dict(os.environ),
        )
        self._session: ClientSession | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None
        # Signals the background loop to shut down
        self._shutdown_event: asyncio.Event | None = None
        # Set once the session is ready
        self._ready = threading.Event()
        self._start_error: BaseException | None = None

    def start(self) -> None:
        """Start the MCP server in a background thread and wait until ready."""
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._run_loop, daemon=True)
        self._thread.start()
        self._ready.wait()
        if self._start_error is not None:
            raise self._start_error

    def _run_loop(self) -> None:
        """Background thread: run the async event loop."""
        asyncio.set_event_loop(self._loop)
        self._loop.run_until_complete(self._session_lifecycle())

    async def _session_lifecycle(self) -> None:
        """Manage the full async lifecycle of the stdio client and session."""
        self._shutdown_event = asyncio.Event()
        try:
            async with stdio_client(self._server_params) as (read, write):
                async with ClientSession(read, write) as session:
                    self._session = session
                    await session.initialize()
                    self._ready.set()
                    # Block until close() signals shutdown
                    await self._shutdown_event.wait()
        except BaseException as exc:
            self._start_error = exc
            self._ready.set()

    def initialize(self) -> None:
        """No-op — initialization happens in start()."""

    def call_tool(self, name: str, arguments: dict | None = None) -> str:
        """Call an MCP tool and return the text content."""
        assert self._session is not None, "Client not started"
        assert self._loop is not None
        future: Future[Any] = asyncio.run_coroutine_threadsafe(
            self._session.call_tool(name, arguments or {}), self._loop
        )
        result = future.result()
        if result.isError:
            texts = [
                c.text for c in result.content
                if isinstance(c, types.TextContent)
            ]
            raise RuntimeError(f"Tool error: {' '.join(texts)}")
        texts = [
            c.text for c in result.content
            if isinstance(c, types.TextContent)
        ]
        return "\n".join(texts)

    def close(self) -> None:
        """Shut down the session and server process."""
        if self._shutdown_event is not None and self._loop is not None:
            self._loop.call_soon_threadsafe(self._shutdown_event.set)
        if self._thread is not None:
            self._thread.join(timeout=10)
            self._thread = None
        if self._loop is not None:
            self._loop.close()
            self._loop = None
        self._session = None
