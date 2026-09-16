"""
A real RabbitMQ in Docker that a test can kill and start again.

Driven through the docker CLI, so it needs Docker and nothing else. The container
is bound to a host port chosen once: Docker gives a restarted container a new
random port, and a broker that comes back somewhere else is not the outage the
tests are about.

The topology is declared from contracts/amqp/topology.json -- the backend's job in
production, done here because no backend runs in these tests.
"""

from __future__ import annotations

import json
import socket
import subprocess
import time
import uuid
from pathlib import Path
from typing import Callable, Iterable

import pika

SERVICE = Path(__file__).resolve().parents[1]
TOPOLOGY = json.loads((SERVICE.parent / "contracts" / "amqp" / "topology.json").read_text())
IMAGE = "rabbitmq:3-management"

EVENTS = TOPOLOGY["exchanges"]["events"]["name"]
ANALYSIS_QUEUE = TOPOLOGY["queues"]["aiAnalysis"]["name"]
DEAD_LETTER_QUEUE = TOPOLOGY["queues"]["deadLetter"]["name"]
SKILL_UPDATED_KEY = TOPOLOGY["routingKeys"]["SKILL_UPDATED"]


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def docker(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=check, timeout=300)


def require_docker() -> None:
    """Fails -- never skips -- when Docker is missing: a suite that skips proves nothing."""
    try:
        docker("version", "--format", "{{.Server.Version}}")
    except (OSError, subprocess.CalledProcessError) as exc:
        raise RuntimeError("these tests need a working Docker (docker CLI and daemon)") from exc


def wait_for(condition: Callable[[], bool], timeout: float, what: str, interval: float = 0.2) -> float:
    """Polls until condition() holds; returns the seconds it took."""
    start = time.monotonic()
    while not condition():
        if time.monotonic() - start > timeout:
            raise AssertionError(f"not within {timeout:g}s: {what}")
        time.sleep(interval)
    return time.monotonic() - start


def envelope(event_id: str) -> bytes:
    """A schema-version-2 SKILL_UPDATED, as the backend publishes it."""
    return json.dumps({
        "eventId": event_id, "eventType": "SKILL_UPDATED", "schemaVersion": 2,
        "occurredAt": "2026-09-16T10:15:30.123Z", "aggregateType": "Student", "aggregateId": "31",
        "collegeId": 1, "traceId": None, "payload": {"studentId": 31, "skillId": 7},
    }).encode()


class Broker:

    def __init__(self) -> None:
        self.name = f"sb-ai-broker-{uuid.uuid4().hex[:10]}"
        self.port = free_port()
        self.url = f"amqp://guest:guest@127.0.0.1:{self.port}/%2F"

    # ------------------------------------------------------------ lifecycle

    def start(self) -> None:
        docker("run", "-d", "--name", self.name, "-p", f"127.0.0.1:{self.port}:5672", IMAGE)
        self.wait_ready(120)
        self._declare_topology()

    def kill(self) -> None:
        docker("kill", self.name)

    def restart(self) -> None:
        docker("start", self.name)
        self.wait_ready(120)

    def remove(self) -> None:
        docker("rm", "-f", self.name, check=False)

    def wait_ready(self, timeout: float) -> float:
        def accepts() -> bool:
            try:
                connection = self._connect()
            except Exception:  # noqa: BLE001 -- not up yet, whatever the error
                return False
            connection.close()
            return True
        return wait_for(accepts, timeout, f"broker {self.name} accepting connections", interval=0.5)

    # ------------------------------------------------------------ messages

    def publish(self, event_ids: Iterable[str]) -> list[str]:
        """Publishes SKILL_UPDATED envelopes, persistent and confirmed, as the relay does."""
        ids = list(event_ids)
        connection = self._connect()
        try:
            channel = connection.channel()
            channel.confirm_delivery()
            for event_id in ids:
                channel.basic_publish(
                    exchange=EVENTS, routing_key=SKILL_UPDATED_KEY, body=envelope(event_id), mandatory=True,
                    properties=pika.BasicProperties(message_id=event_id, delivery_mode=2,
                                                    content_type="application/json",
                                                    headers={"schemaVersion": 2}))
        finally:
            connection.close()
        return ids

    def take_all(self, queue: str = ANALYSIS_QUEUE) -> list[str]:
        """Removes and returns every message id in the queue."""
        ids = []
        connection = self._connect()
        try:
            channel = connection.channel()
            empty = 0
            while empty < 5:
                method, properties, _ = channel.basic_get(queue, auto_ack=True)
                if method is None:
                    empty += 1
                    time.sleep(0.1)
                else:
                    empty = 0
                    ids.append(properties.message_id)
        finally:
            connection.close()
        return ids

    def depth(self, queue: str = ANALYSIS_QUEUE) -> int:
        connection = self._connect()
        try:
            return connection.channel().queue_declare(queue, passive=True).method.message_count
        finally:
            connection.close()

    # ------------------------------------------------------------ internals

    def _connect(self) -> pika.BlockingConnection:
        parameters = pika.URLParameters(self.url)
        parameters.connection_attempts = 1
        parameters.socket_timeout = 2
        return pika.BlockingConnection(parameters)

    def _declare_topology(self) -> None:
        connection = self._connect()
        try:
            channel = connection.channel()
            for exchange in TOPOLOGY["exchanges"].values():
                channel.exchange_declare(exchange["name"], exchange["type"], durable=True)
            queues = TOPOLOGY["queues"]
            channel.queue_declare(ANALYSIS_QUEUE, durable=True, arguments=queues["aiAnalysis"]["arguments"])
            channel.queue_bind(ANALYSIS_QUEUE, EVENTS, queues["aiAnalysis"]["bindingKey"])
            retry = TOPOLOGY["exchanges"]["retry"]["name"]
            for tier in queues["retryTiers"]:
                channel.queue_declare(tier["name"], durable=True, arguments=tier["arguments"])
                channel.queue_bind(tier["name"], retry, tier["name"])
            channel.queue_declare(DEAD_LETTER_QUEUE, durable=True, arguments=queues["deadLetter"]["arguments"])
            channel.queue_bind(DEAD_LETTER_QUEUE, TOPOLOGY["exchanges"]["deadLetter"]["name"], "")
        finally:
            connection.close()
