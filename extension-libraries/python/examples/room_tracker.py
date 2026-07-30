"""Example: track users and furniture in the current room with htools."""
import sys

from g_python.gextension import Extension
from g_python.hmessage import Direction
from g_python.htools import RoomUsers, RoomFurni

ext = Extension({
    "title": "Room tracker",
    "description": "Prints who and what is in the room",
    "version": "1.0",
    "author": "g_python",
}, sys.argv)

room_users = RoomUsers(ext)
room_furni = RoomFurni(ext)


def on_new_users(users):
    for user in users:
        print("+ {} (index {}) at {}".format(user.name, user.index, user.tile))


def on_floor_furni(items):
    print("Room has {} floor furni".format(len(items)))
    for furni in items[:5]:
        print("  furni id={} type_id={} at {}".format(furni.id, furni.type_id, furni.tile))


room_users.on_new_users(on_new_users)
room_furni.on_floor_furni_load(on_floor_furni)

ext.start()
