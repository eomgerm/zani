"""Cross-platform advisory file locking for single-writer output directories.

Two places need the same guarantee: feature extraction must own its output
root, and a training seed must own its seed directory when several are run in
parallel. Both want the same behavior -- fail immediately rather than queue,
so the operator learns a second process is already there instead of silently
waiting.

The lock is advisory and process-scoped. It stops a second *process* from
entering; it does not protect against a hand-edited directory.
"""

from __future__ import annotations

import errno
import sys
from contextlib import suppress
from pathlib import Path
from types import TracebackType
from typing import BinaryIO


class LockUnavailableError(RuntimeError):
    """Raised when another live process already holds the lock."""


def _lock(handle: BinaryIO) -> None:
    handle.seek(0)
    if sys.platform == "win32":
        import msvcrt

        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
    else:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)


def _unlock(handle: BinaryIO) -> None:
    handle.seek(0)
    if sys.platform == "win32":
        import msvcrt

        msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
    else:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


class DirectoryLock:
    """Non-blocking exclusive lock on a lock file inside a directory.

    Usable directly (:meth:`acquire` / :meth:`release`) or as a context
    manager. ``busy_message`` is what the operator sees when the lock is
    already held, so it should name the conflict and the way out.
    """

    def __init__(self, path: Path, *, busy_message: str) -> None:
        self.path = path
        self._busy_message = busy_message
        self._handle: BinaryIO | None = None

    def acquire(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        handle = self.path.open("a+b")
        try:
            # Windows' msvcrt.locking needs at least one byte to lock.
            handle.seek(0, 2)
            if handle.tell() == 0:
                handle.write(b"\0")
                handle.flush()
            try:
                _lock(handle)
            except OSError as error:
                if error.errno not in {errno.EACCES, errno.EAGAIN}:
                    raise
                raise LockUnavailableError(self._busy_message) from error
        except BaseException:
            with suppress(BaseException):
                handle.close()
            raise
        self._handle = handle

    def release(self, *, suppress_errors: bool = False) -> None:
        handle = self._handle
        if handle is None:
            return
        self._handle = None
        release_error: BaseException | None = None
        try:
            _unlock(handle)
        except BaseException as error:
            release_error = error
        finally:
            try:
                handle.close()
            except BaseException as error:
                if release_error is None:
                    release_error = error
        if release_error is not None and not suppress_errors:
            raise release_error

    def __enter__(self) -> DirectoryLock:
        self.acquire()
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        # An error escaping release() would mask the original exception.
        self.release(suppress_errors=exc is not None)


__all__ = ["DirectoryLock", "LockUnavailableError"]
