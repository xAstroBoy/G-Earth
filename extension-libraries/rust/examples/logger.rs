//! A minimal G-Earth extension: logs connection events and outgoing chat.
//!
//! Build:  `cargo build --release --example logger`
//! Then drop `target/release/examples/logger.exe` into G-Earth's `Extensions/`
//! folder (or install it from the Extensions tab).

use gearth_extension::{Direction, Extension};

fn main() {
    Extension::new(
        "RustLogger",
        "you",
        "1.0.0",
        "Logs connection events and outgoing chat",
    )
    .on_connect(|client| {
        let infos = client.packet_infos();
        eprintln!(
            "[logger] game connection started — {} known messages",
            infos.entries().len()
        );
    })
    .on_disconnect(|| eprintln!("[logger] game connection ended"))
    // Resolve by NAME at runtime (works across hotels; ids are server-specific).
    // Adjust the name to your server's chat message if it differs.
    .on(Direction::ToServer, "Chat", |intercept, _client| {
        if let Ok(text) = intercept.packet_mut().read_string() {
            println!("[chat] {text}");
        }
    })
    // See every incoming packet (does not modify/block anything).
    .on_all(Direction::ToClient, |intercept, _client| {
        let _ = intercept.packet().header_id();
    })
    .run();
}
