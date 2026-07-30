"""g_python - enhanced, drop-in compatible interface for writing G-Earth extensions.

Compatible with the official ``g-python`` (sirjonasxx/G-Python) public API, with
correctness fixes (Latin-1 strings everywhere), name-based interception, a
decorator API, typed reads/writes and additional parsers.

Typical usage::

    from g_python.gextension import Extension
    from g_python.hmessage import Direction, HMessage
    from g_python.hpacket import HPacket
    from g_python import hparsers, htools
"""
from .hpacket import HPacket
from .hmessage import HMessage, Direction
from . import hparsers, htools

__all__ = ["HPacket", "HMessage", "Direction", "hparsers", "htools"]
__version__ = "1.0.0"
