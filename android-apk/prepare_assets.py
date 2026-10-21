from pathlib import Path
import shutil
import re
import base64

repo = Path(__file__).resolve().parents[1]
android = repo / "android-apk"
assets = android / "app" / "src" / "main" / "assets"
drawable = android / "app" / "src" / "main" / "res" / "drawable"

if assets.exists():
    shutil.rmtree(assets)
assets.mkdir(parents=True, exist_ok=True)
drawable.mkdir(parents=True, exist_ok=True)

for name in ("index.html", "manifest.json", "sw.js"):
    shutil.copy2(repo / name, assets / name)

shutil.copytree(repo / "icons", assets / "icons")

# Extrae el primer logo PNG embebido en index.html y lo usa como icono real de Android.
html = (repo / "index.html").read_text(encoding="utf-8")
match = re.search(r"data:image/png;base64,([A-Za-z0-9+/=]+)", html)
if not match:
    raise SystemExit("No se encontró el logo Huella embebido en index.html")

(drawable / "huella_logo_exact.png").write_bytes(base64.b64decode(match.group(1)))

print("Assets Huella Premium 4 preparados para Android")
