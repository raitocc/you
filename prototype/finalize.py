from pathlib import Path
import json,re,urllib.request,concurrent.futures
root=Path(__file__).parent
js=(root/'app.js').read_text(encoding='utf-8')
def fetch(name):
    svg=urllib.request.urlopen('https://raw.githubusercontent.com/hugeicons/hugeicons-static/main/icons/'+name+'.svg').read().decode().replace('stroke="#141B34"','stroke="currentColor"').replace('<svg ','<svg aria-hidden="true" focusable="false" ',1)
    (root/'assets/hugeicons'/f'{name}.svg').write_text(svg,encoding='utf-8')
    return name,svg
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: moods=dict(pool.map(fetch,['happy','smile','sleeping','sad-01']))
m=re.search(r'const icons=(.*?);\nconst icon=',js)
icons=json.loads(m[1]);icons.update(moods)
js=js[:m.start(1)]+json.dumps(icons,ensure_ascii=False)+js[m.end(1):]
js=js.replace("开心:['#936222','#ffe6aa','circle']","开心:['#936222','#ffe6aa','happy']").replace("平静:['#466b82','#d9edf9','circle']","平静:['#466b82','#d9edf9','smile']").replace("松弛:['#527348','#e1efd6','circle']","松弛:['#527348','#e1efd6','sleeping']").replace("自在:['#3c7971','#d3efea','circle']","自在:['#3c7971','#d3efea','smile']").replace("低落:['#65728d','#e1e6f3','circle']","低落:['#65728d','#e1e6f3','sad-01']")
(root/'app.js').write_text(js,encoding='utf-8')
html=(root/'index.html').read_text(encoding='utf-8');css=(root/'style.css').read_text(encoding='utf-8')
(root/'YOU-prototype.html').write_text(html.replace('<link rel="stylesheet" href="style.css">','<style>'+css+'</style>').replace('<script src="app.js"></script>','<script>'+js+'</script>'),encoding='utf-8')
readme=(root/'README.md').read_text(encoding='utf-8')
readme=readme.replace('默认态使用官方 Outline；激活态使用基于免费轮廓本地适配的 Filled 图形，并非官方 Pro Filled 资源。','默认态与激活态均使用官方 Outline，不改变轮廓；激活态以绿色强调。')
readme=readme.replace('组图翻页','组图左右拖拽').replace('叠放组图翻页','组图左右拖拽')
readme+='\n本轮更新：瞬间图片保留原比例并全幅展示，组图支持触屏滑动、鼠标拖拽、键盘方向键，带页码及圆点。封面采用叠层导航与渐变淡出。心情使用情绪配色和 Hugeicons 表情图标；圈子选中头像右上角可打开详情、更换头像、查看成员（成员为演示数据）。\n'
(root/'README.md').write_text(readme,encoding='utf-8')
