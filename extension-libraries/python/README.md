# g_python (enhanced)

A drop-in compatible, enhanced replacement for the official
[`g-python`](https://github.com/sirjonasxx/G-Python) library — the Python
interface for writing [G-Earth](https://github.com/sirjonasxx/G-Earth)
extensions.

It keeps the **entire public API** of the official library (so existing
extensions run unchanged) while fixing correctness bugs and adding ergonomic
helpers.

## Why replace the official one?

* **Consistent string encoding.** The official library defaulted
  `append_string` to **UTF-8** but read with **Latin-1** — the *mismatch*
  silently corrupted accented bytes (mottos, figures, room names) and desynced
  every subsequent read. Here read and write agree. Packet **field** strings
  default to **UTF-8**, which is what this Nitro server uses on the wire (the
  Nitro client reads/writes with `TextEncoder`/`TextDecoder("utf-8")`), so
  `read_string()` gives you real text and `append_string()` writes it back
  correctly. The **transport** layer (`HPacket.stringify()`/`from_string()` and
  the G-Earth intercept `longString`) stays byte-preserving ISO-8859-1 so
  arbitrary/binary packet bytes survive the trip through G-Earth intact. Every
  field method still takes an `encoding=` override for the rare exception.
* **`HScoreBoard` is included.** Many scripts do
  `from g_python.hparsers import HScoreBoard`; it was missing upstream.
* **Name-based interception** resolved at runtime from G-Earth's
  `PacketInfoManager` — no hardcoded header ids (essential for Nitro/BSS, where
  ids are server-specific).
* **Fewer foot-guns:** a broken intercept callback is logged instead of
  desyncing the packet stream; unknown G-Earth control messages are ignored
  rather than crashing; the `NULL`→`None` packet-info bug is fixed.

## Install

```bash
cd "extension-libraries/python"
pip install -e .
```

Zero mandatory third-party dependencies (standard-library sockets/threading
only). Python 3.7+.

## Quick start

```python
import sys
from g_python.gextension import Extension
from g_python.hmessage import Direction, HMessage
from g_python.hpacket import HPacket

ext = Extension({
    'title': 'my extension', 'description': '', 'version': '1.0', 'author': 'me',
}, sys.argv)

# Intercept by NAME (resolved at runtime) ...
def on_chat(message: HMessage):
    index, text = message.packet.read('is')
    print(index, text)

ext.intercept(Direction.TO_CLIENT, on_chat, 'Chat')

# ... or by header id, or with the decorator, or for every packet:
@ext.on_intercept(Direction.TO_SERVER, 'Chat')
def on_out(message):
    print(message.packet.read_string())

ext.start()
```

## Compatibility contract

Everything the official library exposed still works verbatim:

| Import | Status |
| --- | --- |
| `from g_python.gextension import Extension` | ✅ |
| `from g_python.hpacket import HPacket` | ✅ |
| `from g_python.hmessage import Direction, HMessage` (and `HPacket`) | ✅ |
| `from g_python import htools, hparsers` | ✅ |
| `from g_python.htools import RoomUsers, RoomFurni, Inventory` | ✅ |
| `from g_python.hparsers import HEntity, HFloorItem, HWallItem, HPoint, HScoreBoard, …` | ✅ |

`Extension`, `intercept`, `start`, `send_to_server`/`send_to_client`
(+ `write_to_server`/`write_to_client` aliases), `HPacket(...)`,
`packet.read(...)`, typed reads, `is_corrupted()`, parser `parse()` classmethods
and field names (`entity.name`, `floor_item.type_id`, …) are all preserved.
`HPacket(header, *objects)` produces **byte-for-byte identical** output to the
official constructor (including its habit of ignoring non str/int/bool/bytes
arguments).

## What's new (all additive)

* **Reads:** `read_int`, `read_uint`, `read_short`, `read_ushort`, `read_long`,
  `read_double`, `read_float`, `read_byte`, `read_bool`, `read_bytes`,
  `read_string`, `read_long_string`. Structured `read('isb')` (returns a list),
  plus `read_tuple`, `peek` (read without advancing), `skip`, `remaining`,
  `reset`, `is_eof`.
* **Writes:** matching `append_*` / `replace_*` for every type, all chainable;
  generic `replace(index, value)`; `append(*objects)`; `append_long_string`.
* **Structure characters** for `read`/`peek`/`skip`:
  `i` int, `s` string, `b` byte, `B` bool, `u` short, `l` long, `d` double,
  `f` float, `S` long (u32-length) string. Unknown characters raise a clear
  `ValueError`.
* **Interception:** name/hash/header-id/all-packets; `async` and `async_modify`
  modes; the `@ext.on_intercept(...)` decorator; `remove_intercept`.
* **PacketInfoManager lookups:** `ext.packet_infos`, `ext.get_header_id(dir, name)`,
  `ext.packet_name(dir, header_id)`.
* **Parsers:** typed, with `__repr__`; added `HScoreBoard`/`HScoreEntry`; a
  `typeId` alias alongside `type_id`; fixed the previously-broken `HGroupMode`
  and `HBubble` enums.
* **Quality:** type hints and docstrings throughout, a `logging` logger
  (`logging.getLogger("g_python")`), safe callback isolation, clean `stop()`.

## Layout

```
g_python/
  __init__.py     # package exports + __version__
  gextension.py   # Extension: the G-Earth connection & interception engine
  hpacket.py      # HPacket: EVA-WIRE packet with typed reads/writes
  hmessage.py     # Direction, HMessage (re-exports HPacket)
  hparsers.py     # HEntity, HFloorItem, HWallItem, HPoint, HScoreBoard, enums, …
  htools.py       # RoomUsers, RoomFurni, Inventory
examples/         # runnable examples
tests/            # decodes real packets.txt through HPacket
```

## Testing

```bash
python tests/test_packets.py
```

Decodes real captured packets (G-Earth stringify format) through `HPacket` and
asserts every field reads back correctly, validating UTF-8 field decoding,
byte-preserving transport, and big-endian framing, plus writer-API round-trips.
