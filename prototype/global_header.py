from pathlib import Path
root=Path(__file__).parent
js=(root/'app.js').read_text(encoding='utf-8')
js=js.replace('function header(){return `<header','function brandHeader(){return `<header',1)
idx=js.rindex("go('瞬间');")
js=js[:idx]+"document.querySelector('.status').insertAdjacentHTML('afterend',brandHeader());\nfunction header(){return ''}\n"+js[idx:]
css=(root/'style.css').read_text(encoding='utf-8')+'''
.phone>.header{position:relative;top:auto;height:98px;z-index:8;background:white}.phone main,.phone.immersive main{height:calc(100% - 138px)}.phone.immersive .status{position:relative;color:var(--ink);text-shadow:none;background:white;pointer-events:auto}.phone.immersive .backbar{padding-top:0;height:66px}.phone.immersive .gallery-count{top:77px}.phone.immersive .time-gallery .gallery-count,.phone.immersive .community-gallery .gallery-count{top:12px}.moment-tools{top:0}.phone>.header+.status{display:none}@media(max-width:800px){.phone main,.phone.immersive main{height:calc(100% - 132px)}}
'''
(root/'app.js').write_text(js,encoding='utf-8');(root/'style.css').write_text(css,encoding='utf-8')
html=(root/'index.html').read_text(encoding='utf-8')
(root/'YOU-prototype.html').write_text(html.replace('<link rel="stylesheet" href="style.css">','<style>'+css+'</style>').replace('<script src="app.js"></script>','<script>'+js+'</script>'),encoding='utf-8')
