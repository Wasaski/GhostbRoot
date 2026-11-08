"""Spec §3 example — receive WebSocket events."""

from ghostbroot import GhostbRoot


def on_event(msg):
    print(msg.get("type"), msg.get("payload"))


gb = GhostbRoot(host="127.0.0.1", port=8765, token="TOKEN")
gb.listen_events(on_event)  # blocks until the server closes the connection
