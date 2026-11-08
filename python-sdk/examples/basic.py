"""Spec §3 example — run after installing the SDK on Termux."""

from ghostbroot import GhostbRoot

gb = GhostbRoot(host="127.0.0.1", port=8765, token="TOKEN")
gb.connect()
print(gb.status())

panel = gb.create_panel(title="Ghost Panel", width=340, height=450)
panel.button("Executar", id="run")
panel.show()
