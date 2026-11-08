"""Spec §3 example — components, canvas drawing and animation."""

from ghostbroot import GhostbRoot

gb = GhostbRoot(host="127.0.0.1", port=8765, token="TOKEN")
gb.connect()

panel = gb.create_panel(title="Ghost Panel", width=340, height=450)
panel.label("Status: pronto", id="lbl")
panel.button("Executar", id="run")
panel.input("Digite algo...", id="entry")
panel.slider(50, 100, id="volume")
panel.switch("Som", checked=True, id="sound")
panel.canvas(width=320, height=200, id="cv")
panel.show()

panel.draw([
    {"op": "clear", "color": "#10141C"},
    {"op": "rect", "x": 10, "y": 10, "width": 120, "height": 60, "color": "#38BDF8"},
    {"op": "circle", "x": 220, "y": 60, "radius": 30, "color": "#F472B6"},
    {"op": "line", "x1": 10, "y1": 120, "x2": 300, "y2": 120, "color": "#FFFFFF", "style": "stroke"},
    {"op": "text", "x": 10, "y": 150, "text": "Ghost", "color": "#FFFFFF", "textSize": 16},
])
panel.animate("fade", 600, **{"from": 0.0, "to": 1.0})
