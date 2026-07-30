"""G-Earth extension client.

Enhanced, drop-in compatible replacement for ``g_python.gextension``.

Protocol summary (extension point of view). Frame = ``[int32 len][int16 header][body]``,
big-endian, strings Latin-1.

Incoming headers:  1 DoubleClick, 2 InfoRequest, 3 PacketIntercept, 4 FlagsCheck,
                   5 ConnectionStart, 6 ConnectionEnd, 7 Init,
                   20 PacketToStringResponse, 21 StringToPacketResponse.
Outgoing headers:  1 ExtensionInfo, 2 ManipulatedPacket, 3 RequestFlags,
                   4 SendMessage, 20 PacketToStringRequest, 21 StringToPacketRequest,
                   98 ExtensionConsoleLog.
"""
from __future__ import annotations

import logging
import socket
import sys
import threading
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Union

from .hpacket import HPacket
from .hmessage import HMessage, Direction

MINIMUM_GEARTH_VERSION = "1.5.0"

logger = logging.getLogger("g_python")


class INCOMING_MESSAGES(Enum):
    ON_DOUBLE_CLICK = 1
    INFO_REQUEST = 2
    PACKET_INTERCEPT = 3
    FLAGS_CHECK = 4
    CONNECTION_START = 5
    CONNECTION_END = 6
    INIT = 7
    PACKET_TO_STRING_RESPONSE = 20
    STRING_TO_PACKET_RESPONSE = 21


class OUTGOING_MESSAGES(Enum):
    EXTENSION_INFO = 1
    MANIPULATED_PACKET = 2
    REQUEST_FLAGS = 3
    SEND_MESSAGE = 4
    PACKET_TO_STRING_REQUEST = 20
    STRING_TO_PACKET_REQUEST = 21
    EXTENSION_CONSOLE_LOG = 98


EXTENSION_SETTINGS_DEFAULT = {"use_click_trigger": False, "can_leave": True, "can_delete": True}
EXTENSION_INFO_REQUIRED_FIELDS = ["title", "description", "version", "author"]

PORT_FLAG = ["--port", "-p"]
FILE_FLAG = ["--filename", "-f"]
COOKIE_FLAG = ["--auth-token", "-c"]


def fill_settings(settings: Optional[dict], defaults: dict) -> dict:
    if settings is None:
        return defaults.copy()
    settings = settings.copy()
    for key, value in defaults.items():
        if key not in settings or settings[key] is None:
            settings[key] = value
    return settings


def get_argument(args: List[str], flags: Union[str, List[str]]) -> Optional[str]:
    if isinstance(flags, str):
        flags = [flags]
    for flag in flags:
        if flag in args:
            index = args.index(flag)
            if 0 <= index < len(args) - 1:
                return args[index + 1]
    return None


class Extension:
    """A G-Earth extension. Register intercepts, then call :meth:`start`.

    Example::

        ext = Extension({'title': 'demo', 'description': '', 'version': '1.0',
                         'author': 'me'}, sys.argv)
        ext.intercept(Direction.TO_SERVER, on_chat, 'Chat')   # by name
        ext.intercept(Direction.TO_CLIENT, on_status, 3010)   # by header id
        ext.start()
    """

    def __init__(self, extension_info: dict, args: List[str],
                 extension_settings: Optional[dict] = None, silent: bool = False):
        if not silent:
            print("Using enhanced g_python (requires G-Earth >= {})".format(MINIMUM_GEARTH_VERSION),
                  file=sys.stderr)

        extension_settings = fill_settings(extension_settings, EXTENSION_SETTINGS_DEFAULT)
        for key in EXTENSION_INFO_REQUIRED_FIELDS:
            if key not in extension_info:
                raise Exception("Extension info error: {} field missing".format(key))

        port_arg = get_argument(args, PORT_FLAG)
        # Default to G-Earth's standard remote-extension port when none is passed,
        # so an extension can be launched directly (`python script.py`) for debugging.
        self.__port = int(port_arg) if port_arg is not None else 9092
        self.__file = get_argument(args, FILE_FLAG)
        self.__cookie = get_argument(args, COOKIE_FLAG)

        self.__sock: Optional[socket.socket] = None
        self.__lost_packets = 0

        self._extension_info = extension_info
        self._extension_settings = extension_settings

        self.connection_info: Optional[dict] = None
        # {Direction: {header_id|name|hash: [packet_info_dict, ...]}}
        self.packet_infos: Optional[Dict[Direction, Dict[Any, List[dict]]]] = None

        self.__await_connect_packet = False

        self.__start_barrier = threading.Barrier(2)
        self.__start_lock = threading.Lock()
        self.__stream_lock = threading.Lock()

        self.__events: Dict[str, List[Callable]] = {}
        self.__intercept_listeners: Dict[Direction, Dict[Any, List[Callable]]] = {
            Direction.TO_CLIENT: {-1: []}, Direction.TO_SERVER: {-1: []}}

        self.__request_lock = threading.Lock()
        self.__response_barrier = threading.Barrier(2)
        self.__response: Any = None

        self.__manipulation_lock = threading.Lock()
        self.__manipulation_event = threading.Event()
        self.__manipulate_messages: List[HMessage] = []

    # ------------------------------------------------------------------ #
    # Low-level frame IO
    # ------------------------------------------------------------------ #
    def __read_gearth_packet(self) -> HPacket:
        """Read one complete frame, reassembling across partial recvs."""
        write_pos = 0
        length_buffer = bytearray(4)
        while write_pos < 4:
            n_read = self.__sock.recv_into(memoryview(length_buffer)[write_pos:])
            if n_read == 0:
                raise EOFError
            write_pos += n_read

        packet_length = int.from_bytes(length_buffer, "big")
        packet_buffer = length_buffer + bytearray(packet_length)
        while write_pos < 4 + packet_length:
            n_read = self.__sock.recv_into(memoryview(packet_buffer)[write_pos:])
            if n_read == 0:
                raise EOFError
            write_pos += n_read

        return HPacket.from_bytes(packet_buffer)

    def __send_to_stream(self, packet: HPacket) -> None:
        with self.__stream_lock:
            self.__sock.sendall(bytes(packet.bytearray))

    # ------------------------------------------------------------------ #
    # Worker threads
    # ------------------------------------------------------------------ #
    def __packet_manipulation_thread(self) -> None:
        while not self.is_closed():
            habbo_message: Optional[HMessage] = None
            while habbo_message is None and not self.is_closed():
                if len(self.__manipulate_messages) > 0:
                    with self.__manipulation_lock:
                        habbo_message = self.__manipulate_messages.pop(0)
                    self.__manipulation_event.clear()
                else:
                    self.__manipulation_event.wait(0.002)
                    self.__manipulation_event.clear()

            if self.is_closed() or habbo_message is None:
                return

            habbo_packet = habbo_message.packet
            habbo_packet.default_extension = self

            # "all packets" listeners for this direction.
            for func in self.__intercept_listeners[habbo_message.direction][-1]:
                self.__safe_call(func, habbo_message)
                habbo_packet.reset()

            # Resolve header id -> also the name/hash aliases registered for it.
            header_id = habbo_packet.header_id()
            potential_intercept_ids = {header_id}
            infos = self.packet_infos
            if infos is not None and header_id in infos[habbo_message.direction]:
                for elem in infos[habbo_message.direction][header_id]:
                    if elem["Name"] is not None:
                        potential_intercept_ids.add(elem["Name"])
                    if elem["Hash"] is not None:
                        potential_intercept_ids.add(elem["Hash"])

            for id in potential_intercept_ids:
                if id in self.__intercept_listeners[habbo_message.direction]:
                    for func in self.__intercept_listeners[habbo_message.direction][id]:
                        self.__safe_call(func, habbo_message)
                        habbo_packet.reset()

            response_packet = HPacket(OUTGOING_MESSAGES.MANIPULATED_PACKET.value)
            response_packet.append_string(repr(habbo_message), head=4, encoding="iso-8859-1")
            self.__send_to_stream(response_packet)

    def __safe_call(self, func: Callable, message: HMessage) -> None:
        try:
            func(message)
        except Exception:  # a broken callback must never desync the packet flow
            logger.exception("Error in intercept callback %r", getattr(func, "__name__", func))

    def __connection_thread(self) -> None:
        t = threading.Thread(target=self.__packet_manipulation_thread, daemon=True)
        t.start()

        while not self.is_closed():
            try:
                packet = self.__read_gearth_packet()
            except Exception:
                if not self.is_closed():
                    self.stop()
                return

            try:
                message_type = INCOMING_MESSAGES(packet.header_id())
            except ValueError:
                logger.debug("Unknown G-Earth message header %s", packet.header_id())
                continue

            if message_type == INCOMING_MESSAGES.INFO_REQUEST:
                self.__send_extension_info()

            elif message_type == INCOMING_MESSAGES.CONNECTION_START:
                host, port, hotel_version, client_identifier, client_type = packet.read("sisss")
                self.__parse_packet_infos(packet)
                self.connection_info = {
                    "host": host, "port": port, "hotel_version": hotel_version,
                    "client_identifier": client_identifier, "client_type": client_type}
                self.__raise_event("connection_start")
                if self.__await_connect_packet:
                    self.__await_connect_packet = False
                    self.__start_barrier.wait()

            elif message_type == INCOMING_MESSAGES.CONNECTION_END:
                self.__raise_event("connection_end")
                self.connection_info = None
                self.packet_infos = None

            elif message_type == INCOMING_MESSAGES.FLAGS_CHECK:
                size = packet.read_int()
                flags = [packet.read_string() for _ in range(size)]
                self.__response = flags
                self.__response_barrier.wait()

            elif message_type == INCOMING_MESSAGES.INIT:
                self.__raise_event("init")
                self.write_to_console(
                    'g_python extension "{}" successfully initialized'.format(
                        self._extension_info["title"]), "green", False)
                self.__await_connect_packet = packet.read_bool()
                if not self.__await_connect_packet:
                    self.__start_barrier.wait()

            elif message_type == INCOMING_MESSAGES.ON_DOUBLE_CLICK:
                self.__raise_event("double_click")

            elif message_type == INCOMING_MESSAGES.PACKET_INTERCEPT:
                habbo_msg_as_string = packet.read_string(head=4, encoding="iso-8859-1")
                habbo_message = HMessage.reconstruct_from_java(habbo_msg_as_string)
                with self.__manipulation_lock:
                    self.__manipulate_messages.append(habbo_message)
                self.__manipulation_event.set()

            elif message_type == INCOMING_MESSAGES.PACKET_TO_STRING_RESPONSE:
                string = packet.read_string(head=4, encoding="iso-8859-1")
                expression = packet.read_string(head=4, encoding="utf-8")
                self.__response = (string, expression)
                self.__response_barrier.wait()

            elif message_type == INCOMING_MESSAGES.STRING_TO_PACKET_RESPONSE:
                packet_string = packet.read_string(head=4, encoding="iso-8859-1")
                self.__response = HPacket.reconstruct_from_java(packet_string)
                self.__response_barrier.wait()

    def __send_extension_info(self) -> None:
        response = HPacket(OUTGOING_MESSAGES.EXTENSION_INFO.value)
        (response
         .append_string(self._extension_info["title"])
         .append_string(self._extension_info["author"])
         .append_string(self._extension_info["version"])
         .append_string(self._extension_info["description"])
         .append_bool(self._extension_settings["use_click_trigger"])
         .append_bool(self.__file is not None)
         .append_string("" if self.__file is None else self.__file)
         .append_string("" if self.__cookie is None else self.__cookie)
         .append_bool(self._extension_settings["can_leave"])
         .append_bool(self._extension_settings["can_delete"]))
        self.__send_to_stream(response)

    def __parse_packet_infos(self, packet: HPacket) -> None:
        incoming: Dict[Any, List[dict]] = {}
        outgoing: Dict[Any, List[dict]] = {}

        length = packet.read_int()
        for _ in range(length):
            header_id, hash_, name, structure, is_outgoing, source = packet.read("isssBs")
            name = None if name == "NULL" else name
            hash_ = None if hash_ == "NULL" else hash_
            structure = None if structure == "NULL" else structure

            elem = {"Id": header_id, "Name": name, "Hash": hash_,
                    "Structure": structure, "Source": source}

            table = outgoing if is_outgoing else incoming
            for key in (header_id, hash_, name):
                if key is not None:
                    table.setdefault(key, []).append(elem)

        self.packet_infos = {Direction.TO_CLIENT: incoming, Direction.TO_SERVER: outgoing}

    # ------------------------------------------------------------------ #
    # Events
    # ------------------------------------------------------------------ #
    def __raise_event(self, event_name: str) -> None:
        if event_name in self.__events:
            callbacks = list(self.__events[event_name])
            threading.Thread(target=self.__run_callbacks, args=(callbacks,), daemon=True).start()

    @staticmethod
    def __run_callbacks(callbacks: List[Callable]) -> None:
        for func in callbacks:
            try:
                func()
            except Exception:
                logger.exception("Error in event callback")

    def on_event(self, event_name: str, func: Callable) -> None:
        """Register a callback for: ``double_click``, ``connection_start``,
        ``connection_end`` or ``init``."""
        self.__events.setdefault(event_name, []).append(func)

    # ------------------------------------------------------------------ #
    # Sending packets
    # ------------------------------------------------------------------ #
    def __send(self, direction: Direction, packet: HPacket) -> bool:
        if self.is_closed():
            self.__lost_packets += 1
            return False

        old_settings = None
        if packet.is_incomplete_packet():
            old_settings = (packet.header_id(), packet.is_edited, packet.incomplete_identifier)
            packet.fill_id(direction, self)

        if self.connection_info is None:
            self.__lost_packets += 1
            logger.warning("Could not send packet: G-Earth isn't connected to a client")
            return False
        if packet.is_corrupted():
            self.__lost_packets += 1
            logger.warning("Could not send corrupted packet")
            return False
        if packet.is_incomplete_packet():
            self.__lost_packets += 1
            logger.warning("Could not send incomplete packet (unknown name/hash %r)",
                           packet.incomplete_identifier)
            return False

        wrapper = HPacket(OUTGOING_MESSAGES.SEND_MESSAGE.value,
                          direction == Direction.TO_SERVER,
                          len(packet.bytearray), bytes(packet.bytearray))
        self.__send_to_stream(wrapper)

        if old_settings is not None:
            packet.replace_short(4, old_settings[0])
            packet.incomplete_identifier = old_settings[2]
            packet.is_edited = old_settings[1]
        return True

    def send_to_client(self, packet: Union[HPacket, str]) -> bool:
        """Inject a packet towards the game client."""
        if isinstance(packet, str):
            packet = self.string_to_packet(packet)
        return self.__send(Direction.TO_CLIENT, packet)

    def send_to_server(self, packet: Union[HPacket, str]) -> bool:
        """Inject a packet towards the server."""
        if isinstance(packet, str):
            packet = self.string_to_packet(packet)
        return self.__send(Direction.TO_SERVER, packet)

    # Aliases (accepted by the official API surface).
    write_to_client = send_to_client
    write_to_server = send_to_server

    # ------------------------------------------------------------------ #
    # Interception
    # ------------------------------------------------------------------ #
    def intercept(self, direction: Direction, callback: Callable[[HMessage], None],
                  id: Union[int, str] = -1, mode: str = "default") -> None:
        """Register a packet interceptor.

        :param direction: ``Direction.TO_CLIENT`` or ``Direction.TO_SERVER``.
        :param callback: called with an :class:`HMessage`.
        :param id: header id (int), packet name/hash (str), or ``-1`` for all
            packets in this direction. Names/hashes resolve at runtime from the
            PacketInfoManager sent on connection start (no hardcoded ids needed).
        :param mode: ``default`` (blocking), ``async`` (non-blocking, read only)
            or ``async_modify`` (non-blocking, may modify; disturbs ordering).
        """
        original_callback = callback

        if mode == "async":
            def callback(hmessage: HMessage):
                copy = HMessage(hmessage.packet, hmessage.direction, hmessage._index, hmessage.is_blocked)
                threading.Thread(target=original_callback, args=[copy], daemon=True).start()

        elif mode == "async_modify":
            def callback_send(hmessage: HMessage):
                original_callback(hmessage)
                if not hmessage.is_blocked:
                    self.__send(hmessage.direction, hmessage.packet)

            def callback(hmessage: HMessage):
                hmessage.is_blocked = True
                copy = HMessage(hmessage.packet, hmessage.direction, hmessage._index, False)
                threading.Thread(target=callback_send, args=[copy], daemon=True).start()

        self.__intercept_listeners[direction].setdefault(id, []).append(callback)

    def on_intercept(self, direction: Direction, id: Union[int, str] = -1, mode: str = "default"):
        """Decorator form of :meth:`intercept`::

            @ext.on_intercept(Direction.TO_CLIENT, 'Chat')
            def on_chat(message):
                ...
        """
        def decorator(func: Callable[[HMessage], None]):
            self.intercept(direction, func, id, mode)
            return func
        return decorator

    def remove_intercept(self, id: Union[int, str] = -1) -> None:
        """Remove interceptors for ``id`` (or every interceptor when omitted)."""
        for direction in self.__intercept_listeners:
            if id == -1:
                self.__intercept_listeners[direction] = {-1: []}
            elif id in self.__intercept_listeners[direction]:
                del self.__intercept_listeners[direction][id]

    # ------------------------------------------------------------------ #
    # PacketInfoManager lookups
    # ------------------------------------------------------------------ #
    def get_header_id(self, direction: Direction, name_or_hash: str) -> Optional[int]:
        """Resolve a packet name/hash to its runtime header id (or ``None``)."""
        infos = self.packet_infos
        if infos is not None and name_or_hash in infos[direction]:
            return infos[direction][name_or_hash][0]["Id"]
        return None

    def packet_name(self, direction: Direction, header_id: int) -> Optional[str]:
        """Resolve a header id to its packet name (or ``None``)."""
        infos = self.packet_infos
        if infos is not None and header_id in infos[direction]:
            for elem in infos[direction][header_id]:
                if elem["Name"] is not None:
                    return elem["Name"]
        return None

    # ------------------------------------------------------------------ #
    # Lifecycle
    # ------------------------------------------------------------------ #
    def is_closed(self) -> bool:
        """True when the extension is not connected to G-Earth."""
        return self.__sock is None or self.__sock.fileno() == -1

    def start(self) -> None:
        """Connect to G-Earth and block until the connection is established."""
        with self.__start_lock:
            if not self.is_closed():
                raise Exception("Attempted to run already-running extension")
            self.__sock = socket.socket()
            self.__sock.connect(("127.0.0.1", self.__port))
            self.__sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            threading.Thread(target=self.__connection_thread, daemon=True).start()
        self.__start_barrier.wait()

    def stop(self) -> None:
        """Close the connection to G-Earth."""
        if self.is_closed():
            return
        try:
            self.__sock.close()
        finally:
            self.__manipulation_event.set()
            self.__raise_event("stop")

    def write_to_console(self, text: str, color: str = "black", mention_title: bool = True) -> None:
        """Write a coloured line to the G-Earth console."""
        prefix = (self._extension_info["title"] + " --> ") if mention_title else ""
        message = "[{}]{}{}".format(color, prefix, text)
        self.__send_to_stream(HPacket(OUTGOING_MESSAGES.EXTENSION_CONSOLE_LOG.value, message))

    # ------------------------------------------------------------------ #
    # Request/response helpers
    # ------------------------------------------------------------------ #
    def __await_response(self, request: HPacket) -> Any:
        with self.__request_lock:
            self.__send_to_stream(request)
            self.__response_barrier.wait()
            result = self.__response
            self.__response = None
        return result

    def packet_to_string(self, packet: HPacket) -> str:
        request = HPacket(OUTGOING_MESSAGES.PACKET_TO_STRING_REQUEST.value)
        request.append_string(repr(packet), 4, "iso-8859-1")
        return self.__await_response(request)[0]

    def packet_to_expression(self, packet: HPacket) -> str:
        request = HPacket(OUTGOING_MESSAGES.PACKET_TO_STRING_REQUEST.value)
        request.append_string(repr(packet), 4, "iso-8859-1")
        return self.__await_response(request)[1]

    def string_to_packet(self, string: str) -> HPacket:
        request = HPacket(OUTGOING_MESSAGES.STRING_TO_PACKET_REQUEST.value)
        # G-Earth reads this request body as UTF-8 (StringToPacketRequest).
        request.append_string(string, 4, "utf-8")
        return self.__await_response(request)

    def request_flags(self) -> List[str]:
        return self.__await_response(HPacket(OUTGOING_MESSAGES.REQUEST_FLAGS.value))
