"""
service_lifecycle.py -- starting the service, and stopping it in the right order.

FastAPI runs a lifespan's code before `yield` at startup and after it at shutdown;
uvicorn gets to "after" when it receives SIGTERM, which is how every deploy and
every `docker stop` ends a process. What happens there decides whether a deploy
loses the analysis that was running.

The order, and why:
  1. Stop the consumer. The delivery being handled finishes and is acknowledged;
     nothing new starts (ResilientConsumer.stop).
  2. Wait for its thread, up to a limit. A handler slower than the limit is left
     behind; its delivery was never acknowledged, so the broker gives it to the
     next consumer. Nothing is lost -- it is processed again.
  3. Only then close what the handler uses. Closing the pool first would fail the
     in-flight analysis at exactly the moment of a deploy.

Separate from main.py so tests_broker/test_graceful_shutdown.py can run this code
under a real uvicorn, receiving a real SIGTERM, without the model or a database.
"""

from __future__ import annotations

import threading
from contextlib import asynccontextmanager
from typing import Any, Callable

#: How long shutdown waits for the in-flight delivery. Under the 30 seconds most
#: orchestrators allow between SIGTERM and SIGKILL, with room to close the pool.
JOIN_TIMEOUT_SECONDS = 15.0


def lifespan_for(
    open_resources: Callable[[], None],
    make_consumer: Callable[[], Any],
    close_resources: Callable[[], None],
    *,
    join_timeout: float = JOIN_TIMEOUT_SECONDS,
    log: Callable[[str], None] = print,
):
    """A FastAPI lifespan that runs a consumer in a thread and shuts it down in order."""

    @asynccontextmanager
    async def lifespan(app: Any):
        open_resources()
        consumer = make_consumer()
        thread = threading.Thread(target=consumer.run_forever, name="amqp-consumer", daemon=True)
        thread.start()
        log("[AMQP] consumer thread started")
        try:
            yield
        finally:
            log("[SHUTDOWN] stopping the AMQP consumer")
            consumer.stop()
            thread.join(timeout=join_timeout)
            if thread.is_alive():
                log(f"[SHUTDOWN] consumer did not stop within {join_timeout:g}s; its delivery is "
                    "unacknowledged and goes back to the queue")
            log("[SHUTDOWN] closing resources")
            close_resources()
            log("[SHUTDOWN] complete")

    return lifespan
