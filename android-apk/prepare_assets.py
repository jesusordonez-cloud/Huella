from pathlib import Path
import shutil

repo = Path(__file__).resolve().parents[1]
android = repo / "android-apk"
assets = android / "app" / "src" / "main" / "assets"

if assets.exists():
    shutil.rmtree(assets)
assets.mkdir(parents=True, exist_ok=True)

for name in ("index.html", "manifest.json", "sw.js"):
    shutil.copy2(repo / name, assets / name)

shutil.copytree(repo / "icons", assets / "icons")

print("Assets Huella Premium 4.1.4 preparados para Android")
