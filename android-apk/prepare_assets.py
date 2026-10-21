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

# Copia el logo exacto de Huella como recurso real de Android.
logo = repo / "icons" / "huella_logo_exact.png"
if not logo.exists():
    raise SystemExit("No se encontró icons/huella_logo_exact.png")
shutil.copy2(logo, drawable / "huella_logo_exact.png")

print("Assets Huella Premium 4.1.1 preparados para Android")
