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

index = assets / "index.html"
text = index.read_text(encoding="utf-8")
needle = "async function deliver(name,blob,isText){\n  if(dirHandle){"
replacement = """async function deliver(name,blob,isText){
  if(window.HuellaAndroid && typeof window.HuellaAndroid.saveBlob === 'function'){
    try{
      const dataUrl=await new Promise((resolve,reject)=>{
        const reader=new FileReader();
        reader.onload=()=>resolve(reader.result);
        reader.onerror=reject;
        reader.readAsDataURL(blob);
      });
      const comma=dataUrl.indexOf(',');
      const payload=comma>=0?dataUrl.slice(comma+1):dataUrl;
      window.HuellaAndroid.saveBlob(name,payload,blob.type||'application/octet-stream');
      return 'descarga';
    }catch(e){}
  }
  if(dirHandle){"""
if needle not in text:
    raise SystemExit("No se encontró el punto de integración nativa en index.html")
index.write_text(text.replace(needle, replacement, 1), encoding="utf-8")
print("Assets Huella preparados para Android")
