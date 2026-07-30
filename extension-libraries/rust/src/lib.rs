//! Write **G-Earth extensions in Rust**, using only the standard library.
//!
//! A release build of a binary that uses this crate is a launchable native
//! G-Earth extension: drop the `.exe` in G-Earth's `Extensions/` folder (G-Earth
//! runs `*.exe` directly) or install it from the Extensions tab.
//!
//! ```no_run
//! use gearth_extension::{Extension, Direction};
//!
//! fn main() {
//!     Extension::new("Hello", "you", "1.0", "prints chat")
//!         .on(Direction::ToServer, "Chat", |i, _client| {
//!             if let Ok(msg) = i.packet_mut().read_string() {
//!                 println!("said: {msg}");
//!             }
//!         })
//!         .run();
//! }
//! ```
//!
//! # Encoding
//! Packet **field** strings are **UTF-8** — this is what modern Nitro/Unity
//! servers use on the wire (the Nitro client reads/writes with
//! `TextEncoder`/`TextDecoder("utf-8")`). The extension-protocol **transport**
//! (the intercept `stringify` that carries a whole raw packet) is handled as
//! raw bytes, so arbitrary/binary packet content survives untouched.

use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::{Arc, Mutex};

// ---------------------------------------------------------------------------
// Extension-protocol header ids (verified against G-Earth's NetworkExtensionCodec).
// Values are from G-Earth's point of view; from the extension's POV we RECEIVE
// "incoming" ids and SEND "outgoing" ids.
// ---------------------------------------------------------------------------
mod inc {
    pub const DOUBLE_CLICK: u16 = 1;
    pub const INFO_REQUEST: u16 = 2;
    pub const PACKET_INTERCEPT: u16 = 3;
    pub const FLAGS_CHECK: u16 = 4;
    pub const CONNECTION_START: u16 = 5;
    pub const CONNECTION_END: u16 = 6;
    pub const INIT: u16 = 7;
}
mod out {
    pub const EXTENSION_INFO: u16 = 1;
    pub const MANIPULATED_PACKET: u16 = 2;
    pub const REQUEST_FLAGS: u16 = 3;
    pub const SEND_MESSAGE: u16 = 4;
    pub const CONSOLE_LOG: u16 = 98;
}

/// Packet travel direction, from the extension's point of view.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Direction {
    ToClient,
    ToServer,
}

impl Direction {
    fn as_java(self) -> &'static str {
        match self {
            Direction::ToClient => "TOCLIENT",
            Direction::ToServer => "TOSERVER",
        }
    }
    fn from_java(s: &str) -> Direction {
        if s == "TOCLIENT" {
            Direction::ToClient
        } else {
            Direction::ToServer
        }
    }
}

// ===========================================================================
// HPacket — a Habbo packet with a read cursor. Field strings are UTF-8.
// Wire layout: [i32 length BE][i16 header BE][body]; length = bytes.len() - 4.
// ===========================================================================
/// A Habbo packet: a big-endian byte buffer with a read cursor.
#[derive(Clone, Debug)]
pub struct HPacket {
    /// Full frame: `[i32 len][i16 header][body]`.
    bytes: Vec<u8>,
    read_index: usize,
    /// Set once the packet has been modified (mirrors G-Earth's edited flag).
    pub is_edited: bool,
}

impl HPacket {
    /// A new, empty packet with the given header id.
    pub fn new(header: u16) -> HPacket {
        let mut p = HPacket {
            bytes: vec![0, 0, 0, 2, 0, 0],
            read_index: 6,
            is_edited: false,
        };
        p.bytes[4..6].copy_from_slice(&header.to_be_bytes());
        p
    }

    /// Parse a packet from a complete wire frame (`[i32 len][i16 header][body]`).
    pub fn from_frame(bytes: Vec<u8>) -> HPacket {
        HPacket {
            bytes,
            read_index: 6,
            is_edited: false,
        }
    }

    /// The header id.
    pub fn header_id(&self) -> u16 {
        u16::from_be_bytes([self.bytes[4], self.bytes[5]])
    }

    /// The complete wire frame, with the length field fixed up.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut b = self.bytes.clone();
        let len = (b.len() as i32 - 4).to_be_bytes();
        b[0..4].copy_from_slice(&len);
        b
    }

    /// Reset the read cursor to just after the header.
    pub fn reset(&mut self) -> &mut Self {
        self.read_index = 6;
        self
    }

    /// Bytes left to read.
    pub fn remaining(&self) -> usize {
        self.bytes.len().saturating_sub(self.read_index)
    }

    /// Whether the cursor is at the end.
    pub fn is_eof(&self) -> bool {
        self.read_index >= self.bytes.len()
    }

    // --- reads -----------------------------------------------------------
    fn take(&mut self, n: usize) -> io::Result<&[u8]> {
        if self.read_index + n > self.bytes.len() {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "packet read past end"));
        }
        let s = &self.bytes[self.read_index..self.read_index + n];
        self.read_index += n;
        Ok(s)
    }

    pub fn read_int(&mut self) -> io::Result<i32> {
        let b = self.take(4)?;
        Ok(i32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }
    pub fn read_short(&mut self) -> io::Result<i16> {
        let b = self.take(2)?;
        Ok(i16::from_be_bytes([b[0], b[1]]))
    }
    pub fn read_ushort(&mut self) -> io::Result<u16> {
        let b = self.take(2)?;
        Ok(u16::from_be_bytes([b[0], b[1]]))
    }
    pub fn read_long(&mut self) -> io::Result<i64> {
        let b = self.take(8)?;
        let mut a = [0u8; 8];
        a.copy_from_slice(b);
        Ok(i64::from_be_bytes(a))
    }
    pub fn read_byte(&mut self) -> io::Result<u8> {
        Ok(self.take(1)?[0])
    }
    pub fn read_bool(&mut self) -> io::Result<bool> {
        Ok(self.read_byte()? != 0)
    }
    pub fn read_bytes(&mut self, n: usize) -> io::Result<Vec<u8>> {
        Ok(self.take(n)?.to_vec())
    }
    /// Read a `u16`-length-prefixed **UTF-8** string (Nitro field encoding).
    pub fn read_string(&mut self) -> io::Result<String> {
        let len = self.read_ushort()? as usize;
        let b = self.take(len)?;
        Ok(String::from_utf8_lossy(b).into_owned())
    }
    /// Read an `i32`-length-prefixed UTF-8 string (G-Earth `longString`).
    pub fn read_long_string(&mut self) -> io::Result<String> {
        let len = self.read_int()? as usize;
        let b = self.take(len)?;
        Ok(String::from_utf8_lossy(b).into_owned())
    }

    // --- writes ----------------------------------------------------------
    pub fn append_int(&mut self, v: i32) -> &mut Self {
        self.bytes.extend_from_slice(&v.to_be_bytes());
        self.is_edited = true;
        self
    }
    pub fn append_short(&mut self, v: i16) -> &mut Self {
        self.bytes.extend_from_slice(&v.to_be_bytes());
        self.is_edited = true;
        self
    }
    pub fn append_ushort(&mut self, v: u16) -> &mut Self {
        self.bytes.extend_from_slice(&v.to_be_bytes());
        self.is_edited = true;
        self
    }
    pub fn append_long(&mut self, v: i64) -> &mut Self {
        self.bytes.extend_from_slice(&v.to_be_bytes());
        self.is_edited = true;
        self
    }
    pub fn append_byte(&mut self, v: u8) -> &mut Self {
        self.bytes.push(v);
        self.is_edited = true;
        self
    }
    pub fn append_bool(&mut self, v: bool) -> &mut Self {
        self.bytes.push(if v { 1 } else { 0 });
        self.is_edited = true;
        self
    }
    pub fn append_bytes(&mut self, v: &[u8]) -> &mut Self {
        self.bytes.extend_from_slice(v);
        self.is_edited = true;
        self
    }
    /// Append a `u16`-length-prefixed **UTF-8** string (Nitro field encoding).
    pub fn append_string(&mut self, v: &str) -> &mut Self {
        let b = v.as_bytes();
        self.append_ushort(b.len() as u16);
        self.bytes.extend_from_slice(b);
        self.is_edited = true;
        self
    }
    /// Append an `i32`-length-prefixed UTF-8 string (G-Earth `longString`).
    pub fn append_long_string(&mut self, v: &str) -> &mut Self {
        let b = v.as_bytes();
        self.append_int(b.len() as i32);
        self.bytes.extend_from_slice(b);
        self.is_edited = true;
        self
    }
}

// ===========================================================================
// PacketInfo / PacketInfoManager — name<->header id resolution at runtime.
// ===========================================================================
/// One entry of G-Earth's message table for the connected hotel.
#[derive(Clone, Debug)]
pub struct PacketInfo {
    pub header_id: i32,
    pub hash: Option<String>,
    pub name: Option<String>,
    pub structure: Option<String>,
    pub outgoing: bool,
    pub source: String,
}

/// The message name<->header table G-Earth sends in `ConnectionStart`.
/// Resolve packets by **name**; header ids are hotel-specific (and fake for Nitro).
#[derive(Clone, Debug, Default)]
pub struct PacketInfoManager {
    by_name_in: HashMap<String, i32>,
    by_name_out: HashMap<String, i32>,
    by_id_in: HashMap<i32, String>,
    by_id_out: HashMap<i32, String>,
    all: Vec<PacketInfo>,
}

impl PacketInfoManager {
    fn from_packet(p: &mut HPacket) -> io::Result<PacketInfoManager> {
        let mut m = PacketInfoManager::default();
        let count = p.read_int()?;
        for _ in 0..count {
            let header_id = p.read_int()?;
            let hash = null_opt(p.read_string()?);
            let name = null_opt(p.read_string()?);
            let structure = null_opt(p.read_string()?);
            let outgoing = p.read_bool()?;
            let source = p.read_string()?;
            if let Some(n) = &name {
                if outgoing {
                    m.by_name_out.insert(n.clone(), header_id);
                    m.by_id_out.insert(header_id, n.clone());
                } else {
                    m.by_name_in.insert(n.clone(), header_id);
                    m.by_id_in.insert(header_id, n.clone());
                }
            }
            m.all.push(PacketInfo { header_id, hash, name, structure, outgoing, source });
        }
        Ok(m)
    }

    /// Resolve a message name to its header id for the given direction.
    pub fn header_id(&self, dir: Direction, name: &str) -> Option<i32> {
        match dir {
            Direction::ToClient => self.by_name_in.get(name).copied(),
            Direction::ToServer => self.by_name_out.get(name).copied(),
        }
    }

    /// Resolve a header id to a message name for the given direction.
    pub fn name(&self, dir: Direction, header_id: i32) -> Option<&str> {
        let m = match dir {
            Direction::ToClient => &self.by_id_in,
            Direction::ToServer => &self.by_id_out,
        };
        m.get(&header_id).map(|s| s.as_str())
    }

    /// All entries, as received.
    pub fn entries(&self) -> &[PacketInfo] {
        &self.all
    }
}

fn null_opt(s: String) -> Option<String> {
    if s == "NULL" {
        None
    } else {
        Some(s)
    }
}

// ===========================================================================
// Intercept — a packet being intercepted; the callback may inspect/edit/block it.
// ===========================================================================
/// A packet passing through G-Earth. Inspect [`Intercept::packet_mut`], mutate
/// it, or [`Intercept::block`] it (then it won't be forwarded).
pub struct Intercept {
    pub direction: Direction,
    pub index: i32,
    pub is_blocked: bool,
    packet: HPacket,
}

impl Intercept {
    /// The intercepted packet (mutable — edits are forwarded).
    pub fn packet_mut(&mut self) -> &mut HPacket {
        &mut self.packet
    }
    /// The intercepted packet (read-only).
    pub fn packet(&self) -> &HPacket {
        &self.packet
    }
    /// Block the packet: it will not reach the client/server.
    pub fn block(&mut self) {
        self.is_blocked = true;
    }

    fn stringify(&self) -> Vec<u8> {
        // blocked \t index \t DIRECTION \t (edited?1:0) + raw packet bytes
        let mut v = Vec::new();
        v.push(if self.is_blocked { b'1' } else { b'0' });
        v.push(b'\t');
        v.extend_from_slice(self.index.to_string().as_bytes());
        v.push(b'\t');
        v.extend_from_slice(self.direction.as_java().as_bytes());
        v.push(b'\t');
        v.push(if self.packet.is_edited { b'1' } else { b'0' });
        v.extend_from_slice(&self.packet.to_bytes());
        v
    }

    fn parse(stringify: &[u8]) -> io::Result<Intercept> {
        // Split on the first three tabs; the 4th field is (editflag + raw bytes).
        let mut parts = Vec::with_capacity(4);
        let mut start = 0;
        for (i, &b) in stringify.iter().enumerate() {
            if b == b'\t' {
                parts.push(&stringify[start..i]);
                start = i + 1;
                if parts.len() == 3 {
                    break;
                }
            }
        }
        if parts.len() != 3 || start >= stringify.len() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "bad HMessage stringify"));
        }
        let is_blocked = parts[0] == b"1";
        let index: i32 = std::str::from_utf8(parts[1])
            .ok()
            .and_then(|s| s.parse().ok())
            .unwrap_or(-1);
        let direction = Direction::from_java(std::str::from_utf8(parts[2]).unwrap_or("TOSERVER"));
        // 4th field: first byte is the edited flag, the rest is the raw packet frame.
        let rest = &stringify[start..];
        let is_edited = rest[0] == b'1';
        let packet_bytes = rest[1..].to_vec();
        let mut packet = HPacket::from_frame(packet_bytes);
        packet.is_edited = is_edited;
        Ok(Intercept { direction, index, is_blocked, packet })
    }
}

type Handler = Box<dyn Fn(&mut Intercept, &Client) + Send + 'static>;

struct Rule {
    direction: Direction,
    /// Match by message name (resolved at runtime) …
    name: Option<String>,
    /// … or by header id, or (both None) match everything in that direction.
    header_id: Option<i32>,
    handler: Handler,
}

/// A handle for sending packets to G-Earth. Passed to every callback and cloneable
/// (`Clone`) so it can be moved into your own threads.
#[derive(Clone)]
pub struct Client {
    stream: Arc<Mutex<TcpStream>>,
    infos: Arc<Mutex<PacketInfoManager>>,
}

impl Client {
    fn raw_send(&self, p: &HPacket) -> io::Result<()> {
        self.stream.lock().unwrap().write_all(&p.to_bytes())
    }
    fn send(&self, dir: Direction, packet: &HPacket) -> io::Result<()> {
        // SEND_MESSAGE body: bool(toServer) + int(frameLen) + frame bytes.
        let mut w = HPacket::new(out::SEND_MESSAGE);
        w.append_bool(dir == Direction::ToServer);
        let frame = packet.to_bytes();
        w.append_int(frame.len() as i32);
        w.append_bytes(&frame);
        self.raw_send(&w)
    }
    /// Inject a packet towards the server.
    pub fn send_to_server(&self, packet: &HPacket) -> io::Result<()> {
        self.send(Direction::ToServer, packet)
    }
    /// Inject a packet towards the client.
    pub fn send_to_client(&self, packet: &HPacket) -> io::Result<()> {
        self.send(Direction::ToClient, packet)
    }
    /// Ask G-Earth for its boot flags (arrives as a FlagsCheck, ignored by default).
    pub fn request_flags(&self) -> io::Result<()> {
        self.raw_send(&HPacket::new(out::REQUEST_FLAGS))
    }
    /// Write a line to G-Earth's extension log.
    pub fn log(&self, text: &str) -> io::Result<()> {
        let mut p = HPacket::new(out::CONSOLE_LOG);
        p.append_string(text);
        self.raw_send(&p)
    }
    /// The runtime message table (name<->id resolution).
    pub fn packet_infos(&self) -> std::sync::MutexGuard<'_, PacketInfoManager> {
        self.infos.lock().unwrap()
    }
}

// ===========================================================================
// Extension — the public entry point.
// ===========================================================================
/// Build and run a G-Earth extension.
pub struct Extension {
    title: String,
    author: String,
    version: String,
    description: String,
    rules: Vec<Rule>,
    on_start: Option<Box<dyn Fn(&Client) + Send + 'static>>,
    on_end: Option<Box<dyn Fn() + Send + 'static>>,
    on_click: Option<Box<dyn Fn() + Send + 'static>>,
}

impl Extension {
    /// Create an extension with the given metadata.
    pub fn new(title: &str, author: &str, version: &str, description: &str) -> Extension {
        Extension {
            title: title.into(),
            author: author.into(),
            version: version.into(),
            description: description.into(),
            rules: Vec::new(),
            on_start: None,
            on_end: None,
            on_click: None,
        }
    }

    /// Intercept packets of a given message **name** and direction.
    pub fn on<F>(mut self, direction: Direction, name: &str, handler: F) -> Self
    where
        F: Fn(&mut Intercept, &Client) + Send + 'static,
    {
        self.rules.push(Rule {
            direction,
            name: Some(name.into()),
            header_id: None,
            handler: Box::new(handler),
        });
        self
    }

    /// Intercept packets by raw **header id** and direction.
    pub fn on_header<F>(mut self, direction: Direction, header_id: i32, handler: F) -> Self
    where
        F: Fn(&mut Intercept, &Client) + Send + 'static,
    {
        self.rules.push(Rule {
            direction,
            name: None,
            header_id: Some(header_id),
            handler: Box::new(handler),
        });
        self
    }

    /// Intercept **every** packet in a direction.
    pub fn on_all<F>(mut self, direction: Direction, handler: F) -> Self
    where
        F: Fn(&mut Intercept, &Client) + Send + 'static,
    {
        self.rules.push(Rule {
            direction,
            name: None,
            header_id: None,
            handler: Box::new(handler),
        });
        self
    }

    /// Called once the game connection starts (message table is resolved; read it
    /// via [`Client::packet_infos`]). Use the [`Client`] to inject packets.
    pub fn on_connect<F: Fn(&Client) + Send + 'static>(mut self, f: F) -> Self {
        self.on_start = Some(Box::new(f));
        self
    }
    /// Called when the game connection ends.
    pub fn on_disconnect<F: Fn() + Send + 'static>(mut self, f: F) -> Self {
        self.on_end = Some(Box::new(f));
        self
    }
    /// Called when the extension is double-clicked in G-Earth.
    pub fn on_double_click<F: Fn() + Send + 'static>(mut self, f: F) -> Self {
        self.on_click = Some(Box::new(f));
        self
    }

    /// Connect to G-Earth (reading `-p/-f/-c` from argv) and run until closed.
    /// Panics on a connection error; use [`Extension::try_run`] to handle it.
    pub fn run(self) {
        if let Err(e) = self.try_run() {
            eprintln!("[gearth-extension] fatal: {e}");
        }
    }

    /// Like [`Extension::run`] but returns the IO error instead of printing it.
    pub fn try_run(self) -> io::Result<()> {
        let args = parse_args();
        let stream = TcpStream::connect(("127.0.0.1", args.port))?;
        stream.set_nodelay(true).ok();
        Runtime::new(self, args, stream)?.event_loop()
    }
}

struct Args {
    port: u16,
    file: Option<String>,
    cookie: Option<String>,
}

fn parse_args() -> Args {
    let argv: Vec<String> = std::env::args().collect();
    let mut port = 9092u16;
    let mut file = None;
    let mut cookie = None;
    let mut i = 1;
    while i + 1 < argv.len() {
        match argv[i].as_str() {
            "-p" => port = argv[i + 1].parse().unwrap_or(9092),
            "-f" => file = Some(argv[i + 1].clone()),
            "-c" => cookie = Some(argv[i + 1].clone()),
            _ => {}
        }
        i += 1;
    }
    Args { port, file, cookie }
}

struct Runtime {
    ext: Extension,
    args: Args,
    read_stream: TcpStream,
    client: Client,
}

impl Runtime {
    fn new(ext: Extension, args: Args, stream: TcpStream) -> io::Result<Runtime> {
        // Split the socket: the event loop owns the read half and blocks on it,
        // while the Client writes through a cloned handle (so injecting a packet
        // from a callback/thread never deadlocks against the blocking read).
        let write = stream.try_clone()?;
        let client = Client {
            stream: Arc::new(Mutex::new(write)),
            infos: Arc::new(Mutex::new(PacketInfoManager::default())),
        };
        Ok(Runtime { ext, args, read_stream: stream, client })
    }

    fn read_frame(&mut self) -> io::Result<HPacket> {
        let mut len_buf = [0u8; 4];
        self.read_stream.read_exact(&mut len_buf)?;
        let len = i32::from_be_bytes(len_buf) as usize;
        let mut body = vec![0u8; len];
        self.read_stream.read_exact(&mut body)?;
        let mut frame = Vec::with_capacity(4 + len);
        frame.extend_from_slice(&len_buf);
        frame.extend_from_slice(&body);
        Ok(HPacket::from_frame(frame))
    }

    fn event_loop(&mut self) -> io::Result<()> {
        loop {
            let mut packet = match self.read_frame() {
                Ok(p) => p,
                Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(()),
                Err(e) => return Err(e),
            };
            match packet.header_id() {
                inc::INFO_REQUEST => self.send_extension_info()?,
                inc::CONNECTION_START => {
                    // host, port, hotelVersion, clientIdentifier, clientType, then packet-info
                    let _host = packet.read_string();
                    let _port = packet.read_int();
                    let _hotel = packet.read_string();
                    let _cid = packet.read_string();
                    let _ctype = packet.read_string();
                    if let Ok(m) = PacketInfoManager::from_packet(&mut packet) {
                        *self.client.infos.lock().unwrap() = m;
                    }
                    if let Some(cb) = &self.ext.on_start {
                        cb(&self.client);
                    }
                }
                inc::CONNECTION_END => {
                    if let Some(cb) = &self.ext.on_end {
                        cb();
                    }
                }
                inc::DOUBLE_CLICK => {
                    if let Some(cb) = &self.ext.on_click {
                        cb();
                    }
                }
                inc::FLAGS_CHECK | inc::INIT => { /* nothing required */ }
                inc::PACKET_INTERCEPT => self.handle_intercept(&mut packet)?,
                _ => { /* unknown control message: ignore */ }
            }
        }
    }

    fn send_extension_info(&mut self) -> io::Result<()> {
        let mut p = HPacket::new(out::EXTENSION_INFO);
        let file = self.args.file.clone().unwrap_or_default();
        let cookie = self.args.cookie.clone().unwrap_or_default();
        let has_file = self.args.file.is_some();
        p.append_string(&self.ext.title)
            .append_string(&self.ext.author)
            .append_string(&self.ext.version)
            .append_string(&self.ext.description)
            .append_bool(self.ext.on_click.is_some())
            .append_bool(has_file)
            .append_string(&file)
            .append_string(&cookie)
            .append_bool(true) // can_leave
            .append_bool(true); // can_delete
        self.client.raw_send(&p)
    }

    fn handle_intercept(&mut self, packet: &mut HPacket) -> io::Result<()> {
        let stringify = packet.read_long_string_raw()?;
        let mut intercept = Intercept::parse(&stringify)?;

        let header = intercept.packet.header_id() as i32;
        let dir = intercept.direction;
        let name = self.client.infos.lock().unwrap().name(dir, header).map(|s| s.to_string());

        for rule in &self.ext.rules {
            if rule.direction != dir {
                continue;
            }
            let matches = match (&rule.name, rule.header_id) {
                (Some(n), _) => name.as_deref() == Some(n.as_str()),
                (None, Some(h)) => h == header,
                (None, None) => true,
            };
            if matches {
                intercept.packet.reset();
                (rule.handler)(&mut intercept, &self.client);
            }
        }

        // Reply with the (possibly edited/blocked) packet.
        let mut reply = HPacket::new(out::MANIPULATED_PACKET);
        let s = intercept.stringify();
        reply.append_int(s.len() as i32);
        reply.append_bytes(&s);
        self.client.raw_send(&reply)
    }
}

impl HPacket {
    /// Read an i32-length-prefixed field as raw bytes (byte-preserving) — used for
    /// the intercept transport, whose content is a whole raw packet, not text.
    fn read_long_string_raw(&mut self) -> io::Result<Vec<u8>> {
        let len = self.read_int()? as usize;
        Ok(self.take(len)?.to_vec())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utf8_field_string_roundtrip() {
        let mut p = HPacket::new(2198);
        p.append_string("café");
        // u16 length prefix = UTF-8 byte count (5), then the UTF-8 bytes.
        assert_eq!(&p.to_bytes()[6..], &[0, 5, b'c', b'a', b'f', 0xc3, 0xa9]);
        p.reset();
        assert_eq!(p.read_string().unwrap(), "café");
    }

    #[test]
    fn mixed_read_write() {
        let mut p = HPacket::new(1);
        p.append_int(7).append_string("hi").append_bool(true).append_short(-3);
        p.reset();
        assert_eq!(p.read_int().unwrap(), 7);
        assert_eq!(p.read_string().unwrap(), "hi");
        assert!(p.read_bool().unwrap());
        assert_eq!(p.read_short().unwrap(), -3);
        assert!(p.is_eof());
    }

    #[test]
    fn intercept_stringify_roundtrip() {
        let mut inner = HPacket::new(1464);
        inner.append_int(250635).append_string("naïve");
        let i = Intercept {
            direction: Direction::ToServer,
            index: 3,
            is_blocked: false,
            packet: inner.clone(),
        };
        let s = i.stringify();
        let parsed = Intercept::parse(&s).unwrap();
        assert_eq!(parsed.direction, Direction::ToServer);
        assert_eq!(parsed.index, 3);
        assert!(!parsed.is_blocked);
        // Raw packet bytes must survive the transport byte-for-byte.
        assert_eq!(parsed.packet.to_bytes(), inner.to_bytes());
    }
}
