# G-Earth extension libraries

Helper libraries and templates for writing G-Earth extensions in several languages.
An extension is a separate process that G-Earth launches and talks to over a local
TCP socket using the G-Earth Extension Protocol.

## How G-Earth launches an extension

Drop a file in G-Earth's `Extensions/` folder (or install it from the Extensions tab).
G-Earth picks a launcher by file type (`ExecutionInfo.java`) and runs it with
`-p <port> -f <filename> -c <cookie>`:

| File        | Launched as            | Good for                                   |
|-------------|------------------------|--------------------------------------------|
| `*.exe`     | run directly           | Rust, C#/.NET self-contained, C/C++, Go    |
| `*.dll`     | `dotnet <path>`        | C#/.NET framework-dependent                 |
| `*.jar`     | `java -jar <path>`     | Java/Kotlin                                 |
| `*.py`      | `python <path>`        | Python                                      |
| `*.js`      | `node <path>`          | Node.js                                     |
| `*.sh`      | run directly           | shell                                       |

The extension connects to `127.0.0.1:<port>`, answers the `InfoRequest` with its
metadata (passing back the `-c` cookie for install auth), and then receives
`ConnectionStart` — which carries a `PacketInfoManager` (the message name↔header
table for the connected hotel). Resolve packets by **name** via that table; do not
hardcode header ids (they are hotel/build-specific, and fake for Nitro).

## Protocol quick facts

- Frame: `[int32 length BE][int16 header BE][body]`.
- Strings on the wire are **ISO-8859-1 (Latin-1)**, not UTF-8.
- Header ids (extension POV): receive `1` DoubleClick, `2` InfoRequest, `3` PacketIntercept,
  `4` FlagsCheck, `5` ConnectionStart, `6` ConnectionEnd, `7` Init; send `1` ExtensionInfo,
  `2` ManipulatedPacket, `3` RequestFlags, `4` SendMessage.
- Ground truth: `G-Earth-Api/.../protocol/HPacket.java`, `HMessage.java`, and
  `.../network/NetworkExtensionCodec.java`.

## Libraries in this folder

- [`rust/`](rust) — dependency-free Rust crate + example; `cargo build --release` produces
  a native `.exe` G-Earth runs directly.
- [`python/`](python) — enhanced `g-python`: drop-in compatible with the official API
  (`g_python.gextension`, `hpacket`, `hmessage`, `htools`, `hparsers`) with correct Latin-1
  encoding, runtime name-based interception, typed reads/parsers, and a decorator API.
