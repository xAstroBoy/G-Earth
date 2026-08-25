"""G-Earth extension that retries an unanswered BSS SecurityTicket handshake.

Install/run this file from G-Earth before opening the hotel.  The extension
captures the account's own SecurityTicket packet and, only when the server has
sent no authentication response, resends that ticket with a current renderer
timestamp.  It never copies another account's session and never fabricates an
Authenticated packet for the client.
"""

from __future__ import annotations

import sys
import threading
import time
from collections import Counter
from typing import Optional

from g_python.gextension import Extension
from g_python.hmessage import Direction, HMessage
from g_python.hpacket import HPacket


AUTH_TIMEOUT_SECONDS = 4.0
RETRY_INTERVAL_SECONDS = 3.0
MAX_SECURITY_TICKET_RETRIES = 3

# Runtime names are preferred.  The numeric values are BSS fallbacks for a
# connection whose local messages file has not loaded names yet.
SECURITY_TICKET_NAME = "SecurityTicket"
AUTHENTICATED_NAME = "Authenticated"
CLIENT_PING_NAME = "ClientPing"
SECURITY_TICKET_FALLBACK = 1461
AUTHENTICATED_FALLBACK = 41
CLIENT_PING_FALLBACK = 1272


extension_info = {
    "title": "BSS Login Recovery",
    "description": "Retries a SecurityTicket when BSS leaves Nitro at 60% without replying",
    "version": "1.1",
    "author": "xAstroBoy",
}

ext = Extension(extension_info, sys.argv)


class LoginAttempt:
    def __init__(
        self,
        generation: int,
        header_id: int,
        ticket: str,
        renderer_time: int,
        username: str,
    ) -> None:
        self.generation = generation
        self.header_id = header_id
        self.ticket = ticket
        self.renderer_time = renderer_time
        self.username = username
        self.captured_at = time.monotonic()
        self.finished = threading.Event()
        self.authenticated = False
        self.first_response: Optional[str] = None

    def current_renderer_time(self) -> int:
        elapsed_ms = int((time.monotonic() - self.captured_at) * 1000)
        return min(2_147_483_647, max(0, self.renderer_time + elapsed_ms))


state_lock = threading.RLock()
generation = 0
active_attempt: Optional[LoginAttempt] = None

# If G-Earth routes an extension-injected packet through interceptors again,
# consume the matching frame here so it cannot start a second recovery worker.
ignored_injected_frames: Counter[bytes] = Counter()


def packet_name(direction: Direction, header_id: int) -> str:
    try:
        return ext.packet_name(direction, header_id) or ""
    except Exception:
        return ""


def ticket_username(ticket: str) -> str:
    parts = ticket.split("|")
    return parts[1] if len(parts) > 1 and parts[1] else "unknown account"


def reset_attempt(reason: str) -> None:
    global active_attempt
    with state_lock:
        if active_attempt is not None:
            active_attempt.finished.set()
        active_attempt = None
        ignored_injected_frames.clear()
    print(f"[Login Recovery] {reason}", flush=True)


def is_current(attempt: LoginAttempt) -> bool:
    with state_lock:
        return active_attempt is attempt and active_attempt.generation == attempt.generation


def inject_retry(attempt: LoginAttempt, retry_number: int) -> bool:
    packet = HPacket(
        attempt.header_id,
        attempt.ticket,
        attempt.current_renderer_time(),
    )
    frame = bytes(packet)
    with state_lock:
        ignored_injected_frames[frame] += 1

    # g-python's public send_to_server() wrapper historically discards the
    # boolean returned by its internal sender, so a successful send normally
    # returns None.  Treat only an explicit False (newer/fixed wrappers) or a
    # missing client connection as failure.
    if ext.connection_info is None:
        sent = False
    else:
        try:
            result = ext.send_to_server(packet)
            sent = result is not False
        except (OSError, RuntimeError) as error:
            print(
                f"[Login Recovery] Retry {retry_number} failed while writing to G-Earth: {error}",
                flush=True,
            )
            sent = False

    if not sent:
        with state_lock:
            ignored_injected_frames[frame] -= 1
            if ignored_injected_frames[frame] <= 0:
                del ignored_injected_frames[frame]
        print(
            f"[Login Recovery] Retry {retry_number} could not be sent because the client connection ended.",
            flush=True,
        )
        return False

    print(
        f"[Login Recovery] Retry {retry_number}/{MAX_SECURITY_TICKET_RETRIES} sent for "
        f"{attempt.username}; waiting for Authenticated.",
        flush=True,
    )
    return True


def recovery_worker(attempt: LoginAttempt) -> None:
    if attempt.finished.wait(AUTH_TIMEOUT_SECONDS) or not is_current(attempt):
        return

    print(
        f"[Login Recovery] BSS sent no authentication response for {attempt.username} "
        f"after {AUTH_TIMEOUT_SECONDS:.1f}s.",
        flush=True,
    )

    for retry_number in range(1, MAX_SECURITY_TICKET_RETRIES + 1):
        if not is_current(attempt) or attempt.finished.is_set():
            return
        inject_retry(attempt, retry_number)
        if attempt.finished.wait(RETRY_INTERVAL_SECONDS):
            return

    if not is_current(attempt):
        return
    print(
        "[Login Recovery] BSS still did not send Authenticated. The socket is alive, but the "
        "server did not complete this account's SecurityTicket handler. Refresh /hotel/v2 for "
        "a fresh ticket; injecting a fake Authenticated packet would not create a server session.",
        flush=True,
    )


def on_outgoing(message: HMessage) -> None:
    global active_attempt, generation

    header_id = message.packet.header_id()
    name = packet_name(Direction.TO_SERVER, header_id)
    if name != SECURITY_TICKET_NAME and header_id != SECURITY_TICKET_FALLBACK:
        return

    frame = bytes(message.packet)
    with state_lock:
        if ignored_injected_frames[frame] > 0:
            ignored_injected_frames[frame] -= 1
            if ignored_injected_frames[frame] <= 0:
                del ignored_injected_frames[frame]
            return

    packet = HPacket.from_bytes(frame)
    try:
        ticket = packet.read_string()
        renderer_time = packet.read_int()
    except Exception as error:
        print(f"[Login Recovery] Could not parse SecurityTicket: {error}", flush=True)
        return

    if not ticket:
        print("[Login Recovery] Ignoring an empty SecurityTicket.", flush=True)
        return

    with state_lock:
        if active_attempt is not None:
            active_attempt.finished.set()
        generation += 1
        attempt = LoginAttempt(
            generation,
            header_id,
            ticket,
            renderer_time,
            ticket_username(ticket),
        )
        active_attempt = attempt

    # Never print or persist the ticket itself: it is a live login credential.
    print(
        f"[Login Recovery] Captured SecurityTicket for {attempt.username}; "
        "watching for the server's Authenticated response.",
        flush=True,
    )
    threading.Thread(target=recovery_worker, args=(attempt,), daemon=True).start()


def on_incoming(message: HMessage) -> None:
    header_id = message.packet.header_id()
    name = packet_name(Direction.TO_CLIENT, header_id)

    with state_lock:
        attempt = active_attempt
        if attempt is None or attempt.finished.is_set():
            return

        if name == AUTHENTICATED_NAME or header_id == AUTHENTICATED_FALLBACK:
            attempt.authenticated = True
            attempt.first_response = AUTHENTICATED_NAME
            attempt.finished.set()
            print(
                f"[Login Recovery] Authenticated received for {attempt.username}; login recovered.",
                flush=True,
            )
            return

        # A ping only proves that the socket is alive; it is not an answer to
        # SecurityTicket and must not cancel the bounded retry sequence.
        if name == CLIENT_PING_NAME or header_id == CLIENT_PING_FALLBACK:
            return

        response = name or f"header {header_id}"
        attempt.first_response = response
        attempt.finished.set()
        print(
            f"[Login Recovery] Server answered SecurityTicket with {response}, not Authenticated; "
            "automatic replay stopped so the explicit server result is preserved.",
            flush=True,
        )


def on_connection_start() -> None:
    reset_attempt("Connected to a new client session; waiting for SecurityTicket.")


def on_connection_end() -> None:
    reset_attempt("Client connection ended; pending login recovery cancelled.")


def main() -> None:
    ext.on_event("connection_start", on_connection_start)
    ext.on_event("connection_end", on_connection_end)

    # One central observer in each direction.  The callbacks do only a small
    # copy/state update; retry waits happen on a separate daemon thread.
    ext.intercept(Direction.TO_SERVER, on_outgoing)
    ext.intercept(Direction.TO_CLIENT, on_incoming)

    print(
        "[Login Recovery] Start this extension before loading BSS. "
        f"Timeout={AUTH_TIMEOUT_SECONDS:.1f}s, retries={MAX_SECURITY_TICKET_RETRIES}.",
        flush=True,
    )
    ext.start()


if __name__ == "__main__":
    main()

