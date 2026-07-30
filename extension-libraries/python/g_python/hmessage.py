"""Direction enum and the HMessage wrapper for intercepted packets.

``HPacket`` is re-exported here so that ``from g_python.hmessage import HPacket``
keeps working (some scripts rely on that).
"""
from __future__ import annotations

from enum import Enum

from .hpacket import HPacket  # noqa: F401  (re-exported on purpose)


class Direction(Enum):
    """Packet travel direction, from the extension's point of view."""
    TO_CLIENT = 0
    TO_SERVER = 1


class HMessage:
    """An intercepted packet plus its direction, flow index and block state."""

    def __init__(self, packet: HPacket, direction: Direction, index: int, is_blocked: bool = False):
        self.packet: HPacket = packet
        self.direction: Direction = direction
        self._index: int = index
        self.is_blocked: bool = is_blocked

    @classmethod
    def reconstruct_from_java(cls, string: str) -> "HMessage":
        """Rebuild from G-Earth's ``HMessage.stringify`` form.

        Layout: ``blocked \\t index \\t DIRECTION \\t packet.stringify``.
        """
        obj = cls.__new__(cls)
        split = string.split("\t", 3)
        obj.is_blocked = split[0] == "1"
        obj._index = int(split[1])
        obj.direction = Direction.TO_CLIENT if split[2] == "TOCLIENT" else Direction.TO_SERVER
        obj.packet = HPacket.reconstruct_from_java(split[3])
        return obj

    def __repr__(self) -> str:
        return "{}\t{}\t{}\t{}".format(
            "1" if self.is_blocked else "0",
            self._index,
            "TOCLIENT" if self.direction == Direction.TO_CLIENT else "TOSERVER",
            repr(self.packet),
        )

    def __eq__(self, other: object) -> bool:
        return (
            isinstance(other, HMessage)
            and self.packet == other.packet
            and self.direction == other.direction
            and self._index == other._index
        )

    def index(self) -> int:
        return self._index

    def block(self) -> None:
        """Mark this packet as blocked (it will not be forwarded)."""
        self.is_blocked = True

    def is_corrupted(self) -> bool:
        return self.packet.is_corrupted()
