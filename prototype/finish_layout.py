from pathlib import Path
root=Path(__file__).parent
js=(root/'app.js').read_text(encoding='utf-8').replace("function editor(){closeSheet();", "function editor(){document.querySelector('.phone').classList.remove('immersive');closeSheet();")
js=js.replace("go('瞬间');", "app.addEventListener('scroll',()=>document.querySelector('.phone').classList.toggle('scrolled',app.scrollTop>55));\ngo('瞬间');")
css=(root/'style.css').read_text(encoding='utf-8')+'''
.list-card{position:relative;display:block;width:100%;margin:0 0 24px;padding:0;border-radius:0;overflow:hidden}.list-card>img{width:100%;height:270px;border-radius:0;object-fit:cover;display:block}.list-card:after{content:"";position:absolute;inset:25% 0 0;background:linear-gradient(transparent,#ffffffd9 80%,white);pointer-events:none}.list-card>div{position:absolute;bottom:17px;left:22px;right:40px;z-index:1}.list-card>svg{position:absolute;bottom:44px;right:22px;z-index:1}.list-card h3{font-size:23px}.list-card p{margin:4px 0;color:#5c6d59}.immersive.scrolled .status{color:#263526;text-shadow:none;background:linear-gradient(white,#fffffff0,transparent)}
'''
(root/'app.js').write_text(js,encoding='utf-8');(root/'style.css').write_text(css,encoding='utf-8')
html=(root/'index.html').read_text(encoding='utf-8')
(root/'YOU-prototype.html').write_text(html.replace('<link rel="stylesheet" href="style.css">','<style>'+css+'</style>').replace('<script src="app.js"></script>','<script>'+js+'</script>'),encoding='utf-8')
