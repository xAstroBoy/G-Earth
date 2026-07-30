"""Smoke test: decode REAL packets from the workspace ``packets.txt`` through
our HPacket and assert every field reads back correctly.

``packets.txt`` uses G-Earth's ``PacketStringUtils`` stringify format, e.g.::

    Incoming[2198] -> [0][0][0][6][8][150][0][2]30
    {in:UnitRemove}{s:"30"}

Each byte is shown either as a literal Latin-1 character or, for unsafe bytes,
as ``[n]`` (0-31, 128-159, and the chars ``[ ] { } DEL``). We rebuild the raw
frame, decode it with :class:`HPacket`, and verify the header id, framing and
every ``{i:}`` / ``{s:}`` / ``{u:}`` / ``{b:}`` / ``{l:}`` value from the
expression line. This validates Latin-1 string handling + big-endian framing.

Run directly:  ``python tests/test_packets.py``
"""
import os
import re
import sys

# Allow running from a source checkout without installing.
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from g_python.hpacket import HPacket  # noqa: E402

PACKET_LINE = re.compile(r"^(Incoming|Outgoing)\[(-?\d+)\]\s*->\s*(.*)$")


def display_to_bytes(display: str) -> bytes:
    """Inverse of PacketStringUtils.toString: '[n]' -> byte n, else Latin-1 char."""
    out = bytearray()
    i = 0
    while i < len(display):
        c = display[i]
        if c == "[":
            j = display.index("]", i)
            out.append(int(display[i + 1:j]))
            i = j + 1
        else:
            out.append(ord(c) & 0xFF)  # each char is a Latin-1 codepoint (0-255)
            i += 1
    return bytes(out)


def parse_expression(expr: str):
    """Tokenise a G-Earth expression line into (type, value) tuples."""
    tokens = []
    i, n = 0, len(expr)
    while i < n:
        if expr[i] != "{":
            i += 1
            continue
        if expr.startswith('{s:"', i):
            j = i + 4
            buf = []
            while j < n:
                ch = expr[j]
                if ch == "\\" and j + 1 < n:
                    nxt = expr[j + 1]
                    if nxt == "\\":
                        buf.append("\\"); j += 2; continue
                    if nxt == '"':
                        buf.append('"'); j += 2; continue
                    if nxt == "r":
                        buf.append("\r"); j += 2; continue
                    buf.append(ch); j += 1; continue
                if ch == '"' and j + 1 < n and expr[j + 1] == "}":
                    j += 2
                    break
                buf.append(ch); j += 1
            tokens.append(("s", "".join(buf)))
            i = j
        else:
            k = expr.index("}", i)
            typ, _, val = expr[i + 1:k].partition(":")
            tokens.append((typ, val))
            i = k + 1
    return tokens


def iter_blocks(path):
    """Yield (direction, header_id, display_line, expression_line_or_None)."""
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")

    i = 0
    while i < len(lines):
        m = PACKET_LINE.match(lines[i])
        if not m:
            i += 1
            continue
        direction, header, display = m.group(1), int(m.group(2)), m.group(3).rstrip("\r")
        expr = None
        # The expression is the next line starting with '{' (display never does,
        # since '{' and '}' are always bracketed as [123]/[125]).
        if i + 1 < len(lines) and lines[i + 1].startswith("{"):
            expr = lines[i + 1].rstrip("\r")
        yield direction, header, display, expr
        i += 1


def check_block(direction, header, display, expr, stats):
    raw = display_to_bytes(display)
    packet = HPacket.from_bytes(raw)

    # Framing + header.
    assert not packet.is_corrupted(), \
        "corrupted framing for {}[{}]: len field {} vs bytes {}".format(
            direction, header, packet.read_int(0), len(packet.bytearray) - 4)
    assert packet.header_id() == header, \
        "header mismatch: read {} expected {}".format(packet.header_id(), header)
    stats["packets"] += 1

    if expr is None:
        return

    packet.reset()
    for idx, (typ, val) in enumerate(parse_expression(expr)):
        if typ in ("h", "in", "out"):
            # Header/identifier token: already validated via the display line.
            if typ == "h":
                assert packet.header_id() == int(val)
            continue
        if typ == "i":
            got = packet.read_int()
            assert got == int(val), "int field {} got {} expected {}".format(idx, got, val)
        elif typ == "u":
            got = packet.read_short()
            assert got == int(val), "ushort field {} got {} expected {}".format(idx, got, val)
        elif typ == "l":
            got = packet.read_long()
            assert got == int(val), "long field {} got {} expected {}".format(idx, got, val)
        elif typ == "s":
            got = packet.read_string()
            assert got == val, "string field {} got {!r} expected {!r}".format(idx, got, val)
        elif typ == "b":
            if val.lower() in ("true", "false"):
                got = packet.read_bool()
                assert got == (val.lower() == "true")
            else:
                got = packet.read_byte()
                assert got == int(val), "byte field {} got {} expected {}".format(idx, got, val)
        elif typ == "d":
            got = packet.read_double()
            assert abs(got - float(val)) < 1e-6
        elif typ == "f":
            got = packet.read_float()
            assert abs(got - float(val)) < 1e-3
        else:
            raise AssertionError("unhandled token type {!r}".format(typ))
        stats["fields"] += 1

    # After consuming the whole expression we should be exactly at EOF.
    assert packet.is_eof(), \
        "trailing bytes after expression for {}[{}]: read_index={} len={}".format(
            direction, header, packet.read_index, len(packet.bytearray))


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    # workspace root is 4 levels up: python/ -> extension-libraries/ -> G-Earth Source/ -> G-Earth/
    candidates = [
        os.path.abspath(os.path.join(here, "..", "..", "..", "..", "packets.txt")),
        os.path.abspath(os.path.join(here, "..", "packets.txt")),
    ]
    path = next((p for p in candidates if os.path.exists(p)), None)
    if path is None:
        print("packets.txt not found in", candidates, file=sys.stderr)
        return 1

    stats = {"packets": 0, "fields": 0, "checked": 0, "skipped": 0}
    failures = []
    for direction, header, display, expr in iter_blocks(path):
        try:
            check_block(direction, header, display, expr, stats)
            stats["checked"] += 1
        except AssertionError as e:
            failures.append((direction, header, str(e)))

    print("packets.txt: {}".format(path))
    print("  blocks decoded ok : {}".format(stats["checked"]))
    print("  packets asserted  : {}".format(stats["packets"]))
    print("  fields asserted   : {}".format(stats["fields"]))
    if failures:
        print("  FAILURES ({}):".format(len(failures)))
        for d, h, msg in failures:
            print("    {}[{}]: {}".format(d, h, msg))
        return 1

    _api_round_trip_tests()
    print("ALL SMOKE TESTS PASSED")
    return 0


def _api_round_trip_tests():
    """Build packets with the writer API and read them back (UTF-8 fields + framing)."""
    # UTF-8 field string round trip (accented motto): this Nitro server is UTF-8,
    # the client uses TextEncoder/TextDecoder('utf-8').
    p = HPacket(2198)
    p.append_string("Sicilianò")  # 'Sicilianò'
    assert not p.is_corrupted()
    p.reset()
    assert p.read_string() == "Sicilianò"
    # 'ò' is its 2-byte UTF-8 encoding (0xC3 0xB2), and the u16 length prefix is
    # the UTF-8 byte count (10), not the char count (9).
    assert bytes(p.bytearray)[-2:] == b"\xc3\xb2"
    assert bytes(p.bytearray[6:8]) == b"\x00\x0a"

    # Mixed structured round trip.
    p = HPacket(1234, 10, "hi", True, -5)
    p.reset()
    a, s, b, i = p.read("isBi")
    assert (a, s, b, i) == (10, "hi", True, -5)
    assert p.is_eof()

    # Typed helpers + peek/remaining/reset.
    p = HPacket(1).append_int(7).append_short(9).append_long(123456789012).append_bool(False)
    p.reset()
    assert p.peek("i") == [7] and p.read_index == 6  # peek does not advance
    assert p.read_int() == 7
    assert p.read_short() == 9
    assert p.read_long() == 123456789012
    assert p.read_bool() is False
    assert p.remaining() == 0

    # replace_string grows the frame and keeps length correct.
    p = HPacket(5, "aa", 1)
    p.replace_string(6, "longer string")
    assert not p.is_corrupted()
    p.reset()
    assert p.read_string() == "longer string"
    assert p.read_int() == 1


if __name__ == "__main__":
    sys.exit(main())
