"""High-level room/inventory state trackers built on top of :mod:`g_python`.

Drop-in compatible with the official ``g_python.htools``. Names/hashes passed to
these trackers are resolved at runtime from the PacketInfoManager, so the same
code works across Flash/Unity/Nitro clients without hardcoded header ids.
"""
from __future__ import annotations

import sys
from typing import Callable, List, Optional

from .gextension import Extension
from .hmessage import HMessage, Direction
from .hpacket import HPacket
from .hparsers import HEntity, HFloorItem, HWallItem, HInventoryItem, HUserUpdate


def validate_headers(ext: Extension, parser_name: str, headers) -> None:
    """Warn (console + stderr) if a tracker was given an unknown name/header."""
    def validate():
        for (header, direction) in headers:
            if header is None:
                error = "Missing headerID/Name in '{}'".format(parser_name)
                print(error, file=sys.stderr)
                ext.write_to_console(error, "red")
            elif isinstance(header, str) and (ext.packet_infos is None
                                              or header not in ext.packet_infos[direction]):
                error = "Invalid headerID/Name in '{}': {}".format(parser_name, header)
                print(error, file=sys.stderr)
                ext.write_to_console(error, "red")

    ext.on_event("connection_start", validate)
    if ext.connection_info is not None:
        validate()


class RoomUsers:
    """Tracks the users/entities currently in the room (by index)."""

    def __init__(self, ext: Extension, room_users="Users", room_model="RoomReady",
                 remove_user="UserRemove", request="GetHeightMap", status="UserUpdate"):
        validate_headers(ext, "RoomUsers", [
            (room_users, Direction.TO_CLIENT),
            (room_model, Direction.TO_CLIENT),
            (remove_user, Direction.TO_CLIENT),
            (request, Direction.TO_SERVER)])

        self.room_users: dict = {}
        self.__callback_new_users: Optional[Callable] = None
        self.__callback_remove_user: Optional[Callable] = None

        self.__ext = ext
        self.__request_id = request

        ext.intercept(Direction.TO_CLIENT, self.__load_room_users, room_users)
        ext.intercept(Direction.TO_CLIENT, self.__clear_room_users, room_model)
        ext.intercept(Direction.TO_CLIENT, self.__remove_user, remove_user)
        ext.intercept(Direction.TO_CLIENT, self.__on_status, status)

    def __remove_user(self, message: HMessage) -> None:
        index = int(message.packet.read_string())
        if index in self.room_users:
            user = self.room_users[index]
            del self.room_users[index]
            if self.__callback_remove_user is not None:
                self.__callback_remove_user(user)

    def __load_room_users(self, message: HMessage) -> None:
        users = HEntity.parse(message.packet)
        for user in users:
            self.room_users[user.index] = user
        if self.__callback_new_users is not None:
            self.__callback_new_users(users)

    def __clear_room_users(self, _: HMessage) -> None:
        self.room_users.clear()

    def on_new_users(self, func: Callable) -> None:
        self.__callback_new_users = func

    def on_remove_user(self, func: Callable) -> None:
        self.__callback_remove_user = func

    def __on_status(self, message: HMessage) -> None:
        self.try_updates(HUserUpdate.parse(message.packet))

    def try_updates(self, updates: List[HUserUpdate]) -> None:
        for update in updates:
            try:
                user = self.room_users[update.index]
                if isinstance(user, HEntity):
                    user.try_update(update)
            except KeyError:
                pass

    def request(self) -> None:
        self.room_users = {}
        self.__ext.send_to_server(HPacket(self.__request_id))


class RoomFurni:
    """Tracks the floor and wall furniture currently in the room."""

    def __init__(self, ext: Extension, floor_items="Objects", wall_items="Items",
                 request="GetHeightMap"):
        validate_headers(ext, "RoomFurni", [
            (floor_items, Direction.TO_CLIENT),
            (wall_items, Direction.TO_CLIENT),
            (request, Direction.TO_SERVER)])

        self.floor_furni: List[HFloorItem] = []
        self.wall_furni: List[HWallItem] = []
        self.__callback_floor_furni: Optional[Callable] = None
        self.__callback_wall_furni: Optional[Callable] = None

        self.__ext = ext
        self.__request_id = request

        ext.intercept(Direction.TO_CLIENT, self.__floor_furni_load, floor_items)
        ext.intercept(Direction.TO_CLIENT, self.__wall_furni_load, wall_items)

    def __floor_furni_load(self, message: HMessage) -> None:
        self.floor_furni = HFloorItem.parse(message.packet)
        if self.__callback_floor_furni is not None:
            self.__callback_floor_furni(self.floor_furni)

    def __wall_furni_load(self, message: HMessage) -> None:
        self.wall_furni = HWallItem.parse(message.packet)
        if self.__callback_wall_furni is not None:
            self.__callback_wall_furni(self.wall_furni)

    def on_floor_furni_load(self, callback: Callable) -> None:
        self.__callback_floor_furni = callback

    def on_wall_furni_load(self, callback: Callable) -> None:
        self.__callback_wall_furni = callback

    def request(self) -> None:
        self.floor_furni = []
        self.wall_furni = []
        self.__ext.send_to_server(HPacket(self.__request_id))


class Inventory:
    """Loads and caches the user's furni inventory (multi-packet aware)."""

    def __init__(self, ext: Extension, inventory_items="FurniList",
                 request="RequestFurniInventory"):
        validate_headers(ext, "Inventory", [
            (inventory_items, Direction.TO_CLIENT),
            (request, Direction.TO_SERVER)])

        self.loaded = False
        self.is_loading = False
        self.inventory_items: List[HInventoryItem] = []
        self.__inventory_items_buffer: List[HInventoryItem] = []

        self.__ext = ext
        self.__request_id = request
        self.__inventory_load_callback: Optional[Callable] = None

        ext.intercept(Direction.TO_CLIENT, self.__user_inventory_load, inventory_items)

    def __user_inventory_load(self, message: HMessage) -> None:
        packet = message.packet
        total, current = packet.read("ii")
        packet.reset()

        items = HInventoryItem.parse(packet)

        if current == 0:  # fresh inventory load
            self.__inventory_items_buffer.clear()
            self.is_loading = True

        self.__inventory_items_buffer.extend(items)

        if current == total - 1:  # last packet
            self.is_loading = False
            self.loaded = True
            self.inventory_items = list(self.__inventory_items_buffer)
            self.__inventory_items_buffer.clear()
            if self.__inventory_load_callback is not None:
                self.__inventory_load_callback(self.inventory_items)

    def request(self) -> None:
        self.__ext.send_to_server(HPacket(self.__request_id))

    def on_inventory_load(self, callback: Callable) -> None:
        self.__inventory_load_callback = callback
