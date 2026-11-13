"""
Keep a pika BlockingConnection alive while a long job runs.

A BlockingConnection only answers broker heartbeats while its own thread is
inside pika's event loop. A scrape job fetches a whole feed inside one
message, with HTTP retries, Retry-After waits and Playwright fallbacks, and
easily blocks for more than the 60 s heartbeat timeout; RabbitMQ then drops
the connection and the worker exits ("missed heartbeats from client").

pika's threaded-consumer pattern fixes this: the message callback starts the
job on a worker thread and returns at once, so start_consuming() keeps
running the event loop (heartbeats included). pika is not thread-safe, so the
job talks to the channel through ThreadSafeChannel, which hands each call to
the connection thread with add_callback_threadsafe and waits for its result.
Those hand-overs only run in the top-level event loop (start_consuming or
process_data_events called outside a message callback), never in a loop
nested inside a callback. With prefetch_count=1 the next message is not
delivered before the job acks or nacks.
"""
from __future__ import annotations

import logging
import threading
from typing import Any, Callable

log = logging.getLogger(__name__)


class ThreadSafeChannel:
    """Channel proxy: calls made from another thread run on the connection's thread."""

    def __init__(self, connection: Any, channel: Any) -> None:
        self._connection = connection
        self._channel = channel
        self._owner = threading.get_ident()

    def _call(self, fn: Callable[[], Any]) -> Any:
        if threading.get_ident() == self._owner:
            return fn()
        done = threading.Event()
        box: dict[str, Any] = {}

        def run() -> None:
            try:
                box["result"] = fn()
            except BaseException as exc:          # re-raised in the calling thread
                box["error"] = exc
            finally:
                done.set()

        self._connection.add_callback_threadsafe(run)
        done.wait()
        if "error" in box:
            raise box["error"]
        return box.get("result")

    def basic_ack(self, **kwargs: Any) -> None:
        self._call(lambda: self._channel.basic_ack(**kwargs))

    def basic_nack(self, **kwargs: Any) -> None:
        self._call(lambda: self._channel.basic_nack(**kwargs))

    def basic_publish(self, **kwargs: Any) -> None:
        self._call(lambda: self._channel.basic_publish(**kwargs))


def start_job(connection: Any, job: Callable[[], None], on_error: Callable[[BaseException], None]) -> threading.Thread:
    """Run job() on a daemon thread and return at once. If it raises,
    on_error(exc) runs on the connection's thread (so it may use the channel).
    If the connection is gone by then, the error is logged; RabbitMQ
    redelivers the unacked message."""

    def target() -> None:
        try:
            job()
        except BaseException as exc:
            try:
                connection.add_callback_threadsafe(lambda err=exc: on_error(err))   # bind now: exc is cleared after except
            except Exception as conn_exc:
                log.error("Job failed (%s) and the connection is gone (%s); the message will be redelivered",
                          exc, conn_exc)

    thread = threading.Thread(target=target, name="amqp-job", daemon=True)
    thread.start()
    return thread
