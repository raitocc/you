"""Build the standalone prototype from the maintained source files."""
from pathlib import Path
import base64
p=Path(__file__).parent
js=(p/'app.js').read_text(encoding='utf-8')
js=js.replace("const avatar='assets/Calmer.png';", "const avatar='data:image/png;base64,"+base64.b64encode((p/'assets/Calmer.png').read_bytes()).decode()+"';")
html=(p/'index.html').read_text(encoding='utf-8')
html=html.replace('<link rel="stylesheet" href="style.css">','<style>'+(p/'style.css').read_text(encoding='utf-8')+'</style>')
html=html.replace('<script src="app.js"></script>','<script>'+js+'</script>')
(p/'YOU-prototype.html').write_text(html,encoding='utf-8')
