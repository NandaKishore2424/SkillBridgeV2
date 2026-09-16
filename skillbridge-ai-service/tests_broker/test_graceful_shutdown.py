"""
SIGTERM, sent to a real uvicorn process, with a delivery in flight.

Every deploy ends the old process this way. What must hold:
  - the delivery being handled finishes, and is acknowledged;
  - nothing new starts;
  - what the handler uses is closed only after it finishes;
  - a handler slower than the shutdown limit does not hold the process up, and
    its message is not lost: never acknowledged, it goes back to the queue.

The service is tests_broker/sigterm_service.py: the production lifespan and
consumer, with a recording handler instead of the model and the database.

Needs Docker, pika, fastapi and uvicorn. Fails, never skips, without Docker.
"""

import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from pathlib import Path

SERVICE = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(SERVICE))

from tests_broker import rabbit  # noqa: E402

BROKER = None


def setUpModule():
    global BROKER
    rabbit.require_docker()
    BROKER = rabbit.Broker()
    BROKER.start()


def tearDownModule():
    if BROKER is not None:
        BROKER.remove()


class GracefulShutdown(unittest.TestCase):

    def setUp(self):
        BROKER.take_all()
        self.log = Path(tempfile.mkstemp(prefix="lifecycle-", suffix=".log")[1])
        self.process = None

    def tearDown(self):
        if self.process is not None and self.process.poll() is None:
            self.process.kill()
            self.process.wait(10)
        self.log.unlink(missing_ok=True)

    def start_service(self, handler_seconds, join_timeout):
        env = dict(os.environ, AMQP_URL=BROKER.url, LIFECYCLE_LOG=str(self.log),
                   HANDLER_SECONDS=str(handler_seconds), JOIN_TIMEOUT=str(join_timeout),
                   PYTHONPATH=os.pathsep.join([str(SERVICE), str(HERE)]))
        self.process = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "sigterm_service:app",
             "--host", "127.0.0.1", "--port", str(rabbit.free_port())],
            cwd=HERE, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    def lines(self):
        return self.log.read_text().splitlines() if self.log.exists() else []

    def text(self):
        return "\n".join(self.lines())

    def sigterm_and_wait(self, limit):
        """
        Sends SIGTERM and waits; fails unless uvicorn shut the application down cleanly.

        Recent uvicorn re-raises the signal once shutdown is complete, so the exit status
        is -SIGTERM, not 0 -- measured with uvicorn 0.53. What matters is that shutdown
        ran to the end first, which its own last lines say.
        """
        self.process.send_signal(signal.SIGTERM)
        start = time.monotonic()
        try:
            self.process.wait(limit)
        except subprocess.TimeoutExpired:
            self.fail(f"the process did not exit within {limit}s of SIGTERM:\n{self.text()}")
        took = time.monotonic() - start
        output = self.process.stdout.read()
        self.assertIn(self.process.returncode, (0, -signal.SIGTERM), output)
        self.assertIn("Application shutdown complete", output)
        return took

    def index_of(self, fragment):
        for i, line in enumerate(self.lines()):
            if fragment in line:
                return i
        self.fail(f"{fragment!r} not in the lifecycle log:\n{self.text()}")

    def test_sigterm_lets_the_running_delivery_finish_and_starts_nothing_new(self):
        ids = BROKER.publish(str(uuid.uuid4()) for _ in range(3))
        self.start_service(handler_seconds=3, join_timeout=15)
        rabbit.wait_for(lambda: f"handling {ids[0]}" in self.text(), 60, "the first delivery in flight")

        took = self.sigterm_and_wait(30)

        # In order: the handler finished, and only then were its resources closed.
        self.assertLess(self.index_of(f"handled {ids[0]}"), self.index_of("resources closed"))
        self.assertEqual(sum("handling" in line for line in self.lines()), 1,
                         f"nothing new may start after SIGTERM:\n{self.text()}")
        # The finished delivery was acknowledged; the other two wait for the next process.
        self.assertEqual(sorted(BROKER.take_all()), sorted(ids[1:]))
        print(f"\n[sigterm] exited {took:.1f}s after SIGTERM, having finished the delivery in flight",
              file=sys.stderr)

    def test_a_handler_slower_than_the_limit_does_not_hold_up_shutdown_and_its_message_is_kept(self):
        ids = BROKER.publish(str(uuid.uuid4()) for _ in range(3))
        self.start_service(handler_seconds=60, join_timeout=2)
        rabbit.wait_for(lambda: f"handling {ids[0]}" in self.text(), 60, "the first delivery in flight")

        took = self.sigterm_and_wait(30)

        self.assertLess(took, 15, "shutdown waited for the handler instead of the limit")
        self.assertIn("did not stop within", self.text())
        self.assertIn("resources closed", self.text())
        self.assertNotIn(f"handled {ids[0]}", self.text())
        # Never acknowledged, so the broker kept it: nothing is lost, it is processed again.
        rabbit.wait_for(lambda: BROKER.depth() == 3, 30, "the unfinished delivery back in the queue")
        self.assertEqual(sorted(BROKER.take_all()), sorted(ids))


if __name__ == "__main__":
    unittest.main()
