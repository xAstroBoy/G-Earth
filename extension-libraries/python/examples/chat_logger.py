"""Minimal example: log chat, both with the classic API and the decorator API.

Run it from G-Earth (Extensions tab -> install this .py) or standalone for
debugging with:  python chat_logger.py -p 9092
"""
import sys

from g_python.gextension import Extension
from g_python.hmessage import Direction, HMessage
from g_python.hpacket import HPacket

extension_info = {
    "title": "Chat logger",
    "description": "Logs incoming/outgoing chat",
    "version": "1.0",
    "author": "g_python",
}

ext = Extension(extension_info, sys.argv)


# Classic API: resolve the packet by NAME at runtime (works on any client,
# no hardcoded header ids needed).
def on_incoming_chat(message: HMessage):
    entity_index, text = message.packet.read("is")
    print("[in ] {}: {}".format(entity_index, text))


ext.intercept(Direction.TO_CLIENT, on_incoming_chat, "Chat")


# Decorator API (an addition; the classic one still works).
@ext.on_intercept(Direction.TO_SERVER, "Chat")
def on_outgoing_chat(message: HMessage):
    text, *_ = message.packet.read("s")
    print("[out] me: {}".format(text))
    if text.strip() == ":ping":
        message.block()  # swallow the command
        ext.send_to_server(HPacket("Chat", "pong", 0, -1))


ext.start()
