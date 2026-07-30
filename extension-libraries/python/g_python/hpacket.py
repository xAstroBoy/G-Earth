"""Habbo packet (EVA-WIRE) implementation for G-Earth extensions.

This is an enhanced, drop-in compatible replacement for the ``g_python.hpacket``
module of the official ``g-python`` library (sirjonasxx/G-Python).

Wire format (all integers big-endian)::

    [int32 length][int16 header][body...]

The ``length`` field counts ``header + body`` (i.e. ``len(bytearray) - 4``).

IMPORTANT: strings are encoded as **ISO-8859-1 (Latin-1)**, matching G-Earth's
``HPacket.java``. The official library defaulted ``append_string`` /
``replace_string`` to UTF-8, which silently desynchronises read positions on any
accented byte (mottos, figure strings, ...). This module defaults to Latin-1
everywhere while keeping the ``encoding=`` parameter for the rare UTF-8 cases
(the extension ``string_to_packet`` request).
"""
from __future__ import annotations

from typing import TYPE_CHECKING, Any, List, Optional, Tuple, Union

if TYPE_CHECKING:  # pragma: no cover - typing only, avoids an import cycle
    from .hmessage import Direction

# Charset used for every Habbo string unless explicitly overridden.
LATIN1 = "iso-8859-1"

# Mapping used by :meth:`HPacket.read` / :meth:`HPacket.peek` / :meth:`HPacket.skip`.
# Mirrors G-Earth's ``PacketStringUtils`` structure characters.
#   i = int32   s = (u16-len) string   b = byte(u8)   B = boolean
#   u = u16     l = int64              d = double     f = float
#   S = (u32-len) "long" string
_STRUCTURE_CHARS = "isbBuldfS"


class HPacket:
    """A mutable Habbo packet with a read cursor.

    Construct it in one of several ways::

        HPacket(3931)                       # empty packet with header id
        HPacket(3931, "hi", 5, True)        # header id + appended values
        HPacket("MoveAvatar", Direction.TO_SERVER)   # name-based (resolved on send)
        HPacket.from_bytes(raw_frame)       # from a complete wire frame
    """

    #: Fallback extension used for name<->packet conversions when none is passed.
    default_extension = None

    # ------------------------------------------------------------------ #
    # Construction
    # ------------------------------------------------------------------ #
    def __init__(self, id: Union[int, str], *objects: Any):
        # A str id means "incomplete" packet whose header is resolved at send
        # time from the runtime PacketInfoManager (name or hash lookup).
        self.incomplete_identifier: Optional[str] = None if isinstance(id, int) else id

        self.read_index: int = 6
        self.bytearray: bytearray = bytearray(b"\x00\x00\x00\x02\xff\xff")
        if self.incomplete_identifier is None:
            self.replace_short(4, id)
        self.is_edited: bool = False

        for obj in objects:
            self._append_compat(obj)

        self.is_edited = False

    def _append_compat(self, obj: Any) -> "HPacket":
        """Append a value the way the official constructor does.

        Only ``str``/``int``/``bool``/``bytes`` are written; any other type is
        silently ignored. This is intentional: the official ``g-python``
        constructor did exactly this, and some scripts rely on the resulting
        (identical) byte output. Use the explicit ``append_*`` methods, or
        :meth:`append_object`, when you want strict behaviour.
        """
        if type(obj) is bool:
            return self.append_bool(obj)
        if type(obj) is int:
            return self.append_int(obj)
        if type(obj) is str:
            return self.append_string(obj)
        if type(obj) is bytes:
            return self.append_bytes(obj)
        return self  # unknown type: ignored for byte-for-byte compatibility

    def fill_id(self, direction: "Direction", extension=None) -> bool:
        """Resolve a name/hash based (incomplete) packet to a real header id.

        Returns ``True`` when the packet has a concrete header id afterwards.
        """
        if self.incomplete_identifier is None:
            return True

        if extension is None:
            extension = self.default_extension
            if extension is None:
                return False

        infos = extension.packet_infos
        if infos is not None and self.incomplete_identifier in infos[direction]:
            edited_old = self.is_edited
            self.replace_short(4, infos[direction][self.incomplete_identifier][0]["Id"])
            self.is_edited = edited_old
            self.incomplete_identifier = None
            return True
        return False

    # https://stackoverflow.com/questions/682504 - alternate constructors
    @classmethod
    def from_bytes(cls, data: Union[bytes, bytearray]) -> "HPacket":
        """Build a packet from a complete wire frame (length + header + body)."""
        obj = cls.__new__(cls)
        obj.bytearray = bytearray(data)
        obj.read_index = 6
        obj.is_edited = False
        obj.incomplete_identifier = None
        return obj

    @classmethod
    def from_string(cls, string: str, extension=None) -> "HPacket":
        """Parse a G-Earth string expression via the connected extension."""
        if extension is None:
            extension = HPacket.default_extension
            if extension is None:
                raise Exception("No extension given for string <-> packet conversion")
        return extension.string_to_packet(string)

    @classmethod
    def reconstruct_from_java(cls, string: str) -> "HPacket":
        """Rebuild from G-Earth's ``HPacket.stringify`` form (edited-flag + bytes)."""
        obj = cls.__new__(cls)
        obj.read_index = 6
        obj.bytearray = bytearray(string[1:].encode(LATIN1))
        obj.is_edited = string[0] == "1"
        obj.incomplete_identifier = None
        return obj

    def copy(self) -> "HPacket":
        """Return an independent copy of this packet."""
        obj = HPacket.from_bytes(self.bytearray)
        obj.is_edited = self.is_edited
        obj.incomplete_identifier = self.incomplete_identifier
        return obj

    # ------------------------------------------------------------------ #
    # Dunder / representation
    # ------------------------------------------------------------------ #
    def __repr__(self) -> str:
        # Matches HPacket.stringify(): edited-flag char + latin-1 bytes.
        return ("1" if self.is_edited else "0") + self.bytearray.decode(LATIN1)

    def __bytes__(self) -> bytes:
        return bytes(self.bytearray)

    def __len__(self) -> int:
        return self.read_int(0)

    def __eq__(self, other: object) -> bool:
        return (
            isinstance(other, HPacket)
            and self.bytearray == other.bytearray
            and self.is_edited == other.is_edited
        )

    def __str__(self) -> str:
        head = self.incomplete_identifier if self.is_incomplete_packet() else self.header_id()
        return "(id:{}, length:{}) -> {}".format(head, len(self), bytes(self))

    # ------------------------------------------------------------------ #
    # State helpers
    # ------------------------------------------------------------------ #
    def is_incomplete_packet(self) -> bool:
        return self.incomplete_identifier is not None

    def is_corrupted(self) -> bool:
        return len(self.bytearray) < 6 or self.read_int(0) != len(self.bytearray) - 4

    def reset(self) -> "HPacket":
        """Rewind the read cursor to the start of the body (index 6)."""
        self.read_index = 6
        return self

    def reset_read_index(self) -> "HPacket":
        return self.reset()

    def remaining(self) -> int:
        """Number of unread bytes left after the read cursor."""
        return len(self.bytearray) - self.read_index

    def is_eof(self) -> bool:
        return self.read_index >= len(self.bytearray)

    def header_id(self) -> int:
        return self.read_short(4)

    def fix_length(self) -> "HPacket":
        edited = self.is_edited
        self.replace_int(0, len(self.bytearray) - 4)
        self.is_edited = edited
        return self

    def g_string(self, extension=None) -> str:
        extension = extension or HPacket.default_extension
        if extension is None:
            raise Exception("No extension given for packet <-> string conversion")
        return extension.packet_to_string(self)

    def g_expression(self, extension=None) -> str:
        extension = extension or HPacket.default_extension
        if extension is None:
            raise Exception("No extension given for packet <-> string conversion")
        return extension.packet_to_expression(self)

    # ------------------------------------------------------------------ #
    # Typed reads (index=None -> read at cursor and advance)
    # ------------------------------------------------------------------ #
    def read_int(self, index: Optional[int] = None) -> int:
        if index is None:
            index = self.read_index
            self.read_index += 4
        return int.from_bytes(self.bytearray[index:index + 4], "big", signed=True)

    def read_uint(self, index: Optional[int] = None) -> int:
        if index is None:
            index = self.read_index
            self.read_index += 4
        return int.from_bytes(self.bytearray[index:index + 4], "big", signed=False)

    def read_short(self, index: Optional[int] = None) -> int:
        if index is None:
            index = self.read_index
            self.read_index += 2
        return int.from_bytes(self.bytearray[index:index + 2], "big", signed=True)

    def read_ushort(self, index: Optional[int] = None) -> int:
        if index is None:
            index = self.read_index
            self.read_index += 2
        return int.from_bytes(self.bytearray[index:index + 2], "big", signed=False)

    def read_long(self, index: Optional[int] = None) -> int:
        if index is None:
            index = self.read_index
            self.read_index += 8
        return int.from_bytes(self.bytearray[index:index + 8], "big", signed=True)

    def read_double(self, index: Optional[int] = None) -> float:
        import struct
        if index is None:
            index = self.read_index
            self.read_index += 8
        return struct.unpack_from(">d", self.bytearray, index)[0]

    def read_float(self, index: Optional[int] = None) -> float:
        import struct
        if index is None:
            index = self.read_index
            self.read_index += 4
        return struct.unpack_from(">f", self.bytearray, index)[0]

    def read_byte(self, index: Optional[int] = None) -> int:
        if index is None:
            index = self.read_index
            self.read_index += 1
        return self.bytearray[index]

    def read_bool(self, index: Optional[int] = None) -> bool:
        return self.read_byte(index) != 0

    def read_bytes(self, length: int, index: Optional[int] = None) -> bytearray:
        if index is None:
            index = self.read_index
            self.read_index += length
        return self.bytearray[index:index + length]

    def read_string(self, index: Optional[int] = None, head: int = 2, encoding: str = LATIN1) -> str:
        if index is None:
            index = self.read_index
            self.read_index += head + int.from_bytes(
                self.bytearray[index:index + head], "big", signed=False)
        length = int.from_bytes(self.bytearray[index:index + head], "big", signed=False)
        return self.bytearray[index + head:index + head + length].decode(encoding)

    def read_long_string(self, index: Optional[int] = None, encoding: str = LATIN1) -> str:
        """Read a string prefixed by a 4-byte length (G-Earth ``longString``)."""
        return self.read_string(index, head=4, encoding=encoding)

    # ------------------------------------------------------------------ #
    # Structured / bulk reads
    # ------------------------------------------------------------------ #
    def _read_one(self, value_type: str) -> Any:
        if value_type == "i":
            return self.read_int()
        if value_type == "s":
            return self.read_string()
        if value_type == "b":
            return self.read_byte()
        if value_type == "B":
            return self.read_bool()
        if value_type == "u":
            return self.read_short()
        if value_type == "l":
            return self.read_long()
        if value_type == "d":
            return self.read_double()
        if value_type == "f":
            return self.read_float()
        if value_type == "S":
            return self.read_long_string()
        raise ValueError(
            "Unknown structure character {!r}; valid characters are {!r}".format(
                value_type, _STRUCTURE_CHARS))

    def read(self, structure: str) -> List[Any]:
        """Read a sequence of values described by a structure string.

        Example::

            room_id, name, owner = packet.read('iss')

        Returns a ``list`` (kept for exact compatibility with the official
        library, whose callers ``.extend()``/``.append()`` on the result).
        """
        return [self._read_one(c) for c in structure]

    def read_tuple(self, structure: str) -> Tuple[Any, ...]:
        """Like :meth:`read` but returns a tuple."""
        return tuple(self.read(structure))

    def peek(self, structure: str) -> List[Any]:
        """Read a structure without advancing the cursor."""
        saved = self.read_index
        try:
            return self.read(structure)
        finally:
            self.read_index = saved

    def skip(self, structure: str) -> "HPacket":
        """Advance the cursor past a structure, discarding the values."""
        self.read(structure)
        return self

    # ------------------------------------------------------------------ #
    # In-place replaces (do not change packet length except replace_string)
    # ------------------------------------------------------------------ #
    def replace_int(self, index: int, value: int) -> "HPacket":
        self.bytearray[index:index + 4] = (value & 0xFFFFFFFF).to_bytes(4, "big")
        self.is_edited = True
        return self

    def replace_short(self, index: int, value: int) -> "HPacket":
        self.bytearray[index:index + 2] = (value & 0xFFFF).to_bytes(2, "big")
        self.is_edited = True
        return self

    def replace_ushort(self, index: int, value: int) -> "HPacket":
        return self.replace_short(index, value)

    def replace_long(self, index: int, value: int) -> "HPacket":
        self.bytearray[index:index + 8] = (value & 0xFFFFFFFFFFFFFFFF).to_bytes(8, "big")
        self.is_edited = True
        return self

    def replace_double(self, index: int, value: float) -> "HPacket":
        import struct
        struct.pack_into(">d", self.bytearray, index, value)
        self.is_edited = True
        return self

    def replace_float(self, index: int, value: float) -> "HPacket":
        import struct
        struct.pack_into(">f", self.bytearray, index, value)
        self.is_edited = True
        return self

    def replace_byte(self, index: int, value: int) -> "HPacket":
        self.bytearray[index] = value & 0xFF
        self.is_edited = True
        return self

    def replace_bool(self, index: int, value: bool) -> "HPacket":
        self.bytearray[index] = 1 if value else 0
        self.is_edited = True
        return self

    def replace_string(self, index: int, value: str, encoding: str = LATIN1) -> "HPacket":
        old_len = self.read_ushort(index)
        part1 = self.bytearray[0:index]
        part3 = self.bytearray[index + 2 + old_len:]

        encoded = value.encode(encoding)
        part2 = len(encoded).to_bytes(2, "big", signed=False) + encoded

        self.bytearray = part1 + part2 + part3
        self.fix_length()
        self.is_edited = True
        return self

    def replace(self, index: int, value: Any) -> "HPacket":
        """Generic in-place replace, dispatching on the Python type of ``value``.

        Convenience helper (bool -> byte, int -> int32, float -> double,
        str -> string, bytes -> raw). For explicit widths use the typed
        ``replace_*`` methods.
        """
        if isinstance(value, bool):
            return self.replace_bool(index, value)
        if isinstance(value, int):
            return self.replace_int(index, value)
        if isinstance(value, float):
            return self.replace_double(index, value)
        if isinstance(value, str):
            return self.replace_string(index, value)
        if isinstance(value, (bytes, bytearray)):
            self.bytearray[index:index + len(value)] = value
            self.is_edited = True
            return self
        raise ValueError("Cannot replace with value of type {}".format(type(value)))

    # ------------------------------------------------------------------ #
    # Appends (all chainable)
    # ------------------------------------------------------------------ #
    def append_int(self, value: int) -> "HPacket":
        self.bytearray.extend((value & 0xFFFFFFFF).to_bytes(4, "big"))
        return self.fix_length()._mark_edited()

    def append_short(self, value: int) -> "HPacket":
        self.bytearray.extend((value & 0xFFFF).to_bytes(2, "big"))
        return self.fix_length()._mark_edited()

    def append_ushort(self, value: int) -> "HPacket":
        return self.append_short(value)

    def append_long(self, value: int) -> "HPacket":
        self.bytearray.extend((value & 0xFFFFFFFFFFFFFFFF).to_bytes(8, "big"))
        return self.fix_length()._mark_edited()

    def append_double(self, value: float) -> "HPacket":
        import struct
        self.bytearray.extend(struct.pack(">d", value))
        return self.fix_length()._mark_edited()

    def append_float(self, value: float) -> "HPacket":
        import struct
        self.bytearray.extend(struct.pack(">f", value))
        return self.fix_length()._mark_edited()

    def append_byte(self, value: int) -> "HPacket":
        self.bytearray.append(value & 0xFF)
        return self.fix_length()._mark_edited()

    def append_bytes(self, value: Union[bytes, bytearray]) -> "HPacket":
        self.bytearray.extend(value)
        return self.fix_length()._mark_edited()

    def append_bool(self, value: bool) -> "HPacket":
        self.bytearray.append(1 if value else 0)
        return self.fix_length()._mark_edited()

    def append_string(self, value: str, head: int = 2, encoding: str = LATIN1) -> "HPacket":
        encoded = value.encode(encoding)
        self.bytearray.extend(len(encoded).to_bytes(head, "big", signed=False))
        self.bytearray.extend(encoded)
        return self.fix_length()._mark_edited()

    def append_long_string(self, value: str, encoding: str = LATIN1) -> "HPacket":
        """Append a string prefixed by a 4-byte length (G-Earth ``longString``)."""
        return self.append_string(value, head=4, encoding=encoding)

    def append_object(self, obj: Any) -> "HPacket":
        """Append a single value, choosing the wire type from ``obj``'s type.

        bool -> byte, int -> int32, str -> string, bytes -> raw. Kept
        deliberately narrow to match the official constructor's behaviour.
        """
        if isinstance(obj, bool):
            return self.append_bool(obj)
        if isinstance(obj, int):
            return self.append_int(obj)
        if isinstance(obj, str):
            return self.append_string(obj)
        if isinstance(obj, (bytes, bytearray)):
            return self.append_bytes(obj)
        raise ValueError("Cannot append value of type {}".format(type(obj)))

    def append(self, *objects: Any) -> "HPacket":
        """Append several values in order (chainable).

        Mirrors the constructor: only str/int/bool/bytes are written, other
        types are ignored. For strict behaviour call :meth:`append_object`.
        """
        for obj in objects:
            self._append_compat(obj)
        return self

    def _mark_edited(self) -> "HPacket":
        self.is_edited = True
        return self
