from pathlib import Path
import shutil

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
shutil.copy2(repo / "icons" / "icon-512.png", drawable / "icon.png")

print("Assets Huella preparados para Android")
