"""A long job started from a consumer callback must not cost the connection its heartbeats.

Runs against the local RabbitMQ (RABBITMQ_URL) with a 2 s heartbeat and a 10 s
job; skipped when the broker is unreachable. The control case shows the same
job run inside the callback (the old behaviour) loses the connection, so the
test can see the bug.
"""
import os
import threading
import time
import unittest
import uuid

import pika
import pika.exceptions

from amqp_keepalive import ThreadSafeChannel, start_job

JOB_SECONDS = 10
HEARTBEAT = 2


def _params() -> pika.URLParameters:
    params = pika.URLParameters(os.environ.get("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/%2F"))
    params.heartbeat = HEARTBEAT
    return params


def _broker_available() -> bool:
    try:
        pika.BlockingConnection(_params()).close()
        return True
    except Exception:
        return False


@unittest.skipUnless(_broker_available(), "RabbitMQ not reachable")
class KeepaliveTest(unittest.TestCase):

    def setUp(self):
        self.connection = pika.BlockingConnection(_params())
        self.channel = self.connection.channel()
        self.channel.basic_qos(prefetch_count=1)
        self.jobs = self.channel.queue_declare(queue=f"it-keepalive-{uuid.uuid4().hex}", exclusive=True).method.queue
        self.out = self.channel.queue_declare(queue=f"it-keepalive-out-{uuid.uuid4().hex}", exclusive=True).method.queue
        self.channel.basic_publish("", self.jobs, b"job")
        self.finished = threading.Event()

    def tearDown(self):
        if self.connection.is_open:
            self.connection.close()

    def _run_loop(self, callback):
        """The worker's top-level loop (start_consuming), bounded for the test."""
        self.channel.basic_consume(queue=self.jobs, on_message_callback=callback)
        deadline = time.monotonic() + JOB_SECONDS + 10
        while not self.finished.is_set() and time.monotonic() < deadline:
            self.connection.process_data_events(time_limit=0.5)

    def _count(self, queue):
        return self.channel.queue_declare(queue=queue, passive=True).method.message_count

    def test_long_job_keeps_connection_and_acks_and_publishes_from_worker_thread(self):
        safe = ThreadSafeChannel(self.connection, self.channel)

        def callback(_ch, method, _props, _body):
            def job():
                time.sleep(JOB_SECONDS)                                   # e.g. slow fetches with retries
                safe.basic_publish(exchange="", routing_key=self.out, body=b"saved")
                safe.basic_ack(delivery_tag=method.delivery_tag)
                self.finished.set()
            start_job(self.connection, job, on_error=lambda exc: self.fail(exc))

        self._run_loop(callback)

        self.assertTrue(self.finished.is_set())
        self.assertTrue(self.connection.is_open)
        self.assertEqual(self._count(self.jobs), 0)                         # acked
        self.assertEqual(self._count(self.out), 1)                          # published

    def test_job_error_runs_handler_on_connection_thread(self):
        main = threading.get_ident()
        seen = {}

        def callback(ch, method, _props, _body):
            def on_error(exc):
                seen["thread"], seen["exc"] = threading.get_ident(), exc
                ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
                self.finished.set()
            start_job(self.connection, lambda: (_ for _ in ()).throw(ValueError("boom")), on_error)

        self._run_loop(callback)

        self.assertIsInstance(seen.get("exc"), ValueError)
        self.assertEqual(seen.get("thread"), main)
        self.assertTrue(self.connection.is_open)
        self.assertEqual(self._count(self.jobs), 0)

    def test_control_job_inside_callback_loses_the_connection(self):
        def callback(ch, method, _props, _body):
            time.sleep(JOB_SECONDS)                                        # the old behaviour
            ch.basic_ack(delivery_tag=method.delivery_tag)
            self.finished.set()

        with self.assertRaises(pika.exceptions.AMQPError):
            self._run_loop(callback)


if __name__ == "__main__":
    unittest.main()
