"""
A stand-in for main.py, for tests_broker/test_graceful_shutdown.py.

The same lifespan (service_lifecycle.lifespan_for) and the same consumer
(amqp_consumer.build) as the real service, run by a real uvicorn. The handler
only records what it does, with timestamps, and takes HANDLER_SECONDS -- so a test
can send SIGTERM while a delivery is in flight and read back what happened, in
what order. No model, no database: they are what makes main.py impossible to
start in a test, and the shutdown order does not depend on them.

Environment: AMQP_URL, LIFECYCLE_LOG, HANDLER_SECONDS, JOIN_TIMEOUT.
"""

import os
import time

from fastapi import FastAPI

import amqp_consumer
import service_lifecycle
from resilient_consumer import Outcome

_log = open(os.environ["LIFECYCLE_LOG"], "a", buffering=1)


def log(line: str) -> None:
    _log.write(f"{time.monotonic():.3f} {line}\n")


def handle(body: bytes, properties) -> Outcome:
    log(f"handling {properties.message_id}")
    time.sleep(float(os.environ.get("HANDLER_SECONDS", "3")))
    log(f"handled {properties.message_id}")
    return Outcome.PROCESSED


app = FastAPI(lifespan=service_lifecycle.lifespan_for(
    lambda: log("resources opened"),
    lambda: amqp_consumer.build(os.environ["AMQP_URL"], handle),
    lambda: log("resources closed"),
    join_timeout=float(os.environ.get("JOIN_TIMEOUT", "15")),
    log=log,
))
