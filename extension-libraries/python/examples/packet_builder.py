"""Example: the typed read/write helpers and the fluent builder.

This one does not need G-Earth - run it directly:  python packet_builder.py
"""
from g_python.hpacket import HPacket

# Fluent, chainable writer. Strings are Latin-1 (matches the Habbo server).
packet = (HPacket(3931)
          .append_int(42)
          .append_string("Sicilianò")   # accented char -> single 0xF2 byte
          .append_bool(True)
          .append_short(7))

print("built:", packet)
print("corrupted?", packet.is_corrupted())

# Typed reads (cursor-based).
packet.reset()
print("int   :", packet.read_int())
print("string:", packet.read_string())
print("bool  :", packet.read_bool())
print("short :", packet.read_short())
print("eof?  :", packet.is_eof())

# Structured read + peek + remaining.
packet.reset()
print("peek 'is':", packet.peek("is"), "(cursor unchanged, remaining", packet.remaining(), "bytes)")
a, s = packet.read("is")
print("read  'is':", a, repr(s))

# Name-based packet (header resolved on send by the extension).
named = HPacket("MoveAvatar")
print("incomplete packet:", named.is_incomplete_packet(), "->", named)
