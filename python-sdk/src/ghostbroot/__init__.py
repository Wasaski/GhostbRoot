"""GhostbRoot Python SDK (spec §3).

Zero third-party dependencies: HTTP via urllib, WebSocket via stdlib socket.
Install in Termux:

    pip install ./python-sdk
"""

from .client import GhostbRoot, GhostbRootError, GhostbRootAuthError, GhostbRootConnectionError
from .models import Panel

__all__ = [
    "GhostbRoot",
    "Panel",
    "GhostbRootError",
    "GhostbRootAuthError",
    "GhostbRootConnectionError",
]

__version__ = "1.0.0"
