from pathlib import Path
import json,re
root=Path(__file__).parent
js=(root/'app.js').read_text(encoding='utf-8')
# Keep official outline geometries only, with color indicating selection.
match=re.search(r'const tabIcons=(.*?);\nconst tabIcon=',js)
tabs=json.loads(match[1]);tabs={n:{'outline':v['outline']} for n,v in tabs.items()}
js=js[:match.start(1)]+json.dumps(tabs,ensure_ascii=False)+js[match.end(1):]
js=js.replace("tabIcons[name][active?'filled':'outline']","tabIcons[name].outline")
js=js.replace('// Official Hugeicons outlines; filled variants adapted locally for this prototype.','// Official Hugeicons outlines; selection changes color only.')
js=js.replace("function go(t){tab=t;", "function go(t){document.querySelector('.phone').classList.remove('immersive');tab=t;")
js=js.replace('<span class="mood">${esc(m.mood)} · ${m.images.length>1?m.images.length+\' 张\':\'此刻\'}</span>', '${moodBadge(m.mood)}')
js=js.replace('<span class="mood">${esc(m.mood)}</span>', '${moodBadge(m.mood)}')
js=js.replace('<span class="pill">此刻 · ${esc(m.mood)}</span>', '${moodBadge(m.mood)}')
js=js.replace('<p class="tags"># ${esc(m.tag)}</p>','<p class="tags">${tagBadge(m.tag)}</p>')
# Replace the moment's cropped, button-driven viewer with a natural-ratio carousel.
start=js.index('function detail(id)')
end=js.index('\nfunction detailComment',start)
detail=js[start:end]
a=detail.index('${m.images.length?')
b=detail.index('<div class="detail-body">',a)
detail=detail[:a]+"${m.images.length?gallery(m):''}"+detail[b:]
# Put header over imagery, then author and body below it.
detail=detail.replace("app.innerHTML=backbar(","app.innerHTML=(m.images.length?'<div class=\"moment-cover\">':'')+backbar(",1)
a=detail.index('`<div class="post-head"')
b=detail.index('${m.images.length?gallery(m)',a)
author=detail[a+1:b]
detail=detail[:a]+"`${m.images.length?gallery(m):''}${m.images.length?'</div>':''}"+author+detail[b+len("${m.images.length?gallery(m):''}"):]
detail=detail.replace('slide[id]=0;app.scrollTop=0','if(m.images.length)immersive();else document.querySelector(\'.phone\').classList.remove(\'immersive\');app.scrollTop=0')
js=js[:start]+detail+js[end:]
# Full-width immersive cover for companion details.
start=js.index('function tripDetail(i)');end=js.index('\nfunction manageTrip',start)
trip=js[start:end]
a=trip.index('app.innerHTML=');b=trip.index('<div class="section-label">',a)
trip=trip[:a]+"app.innerHTML=coverHero(t.image,t.name,`${t.people} 人同行 · ${t.status}`,ib('more',`manageTrip(${i})`,'管理同行'))+`"+trip[b:]
trip=trip.replace('app.scrollTop=0','immersive();app.scrollTop=0')
js=js[:start]+trip+js[end:]
# Remove old carousel/button implementation and overridden functions.
for name in ['flip','timeline','timeDetail','companions','circlePage','circleManage']:
    js=re.sub(r'^function '+name+r'\([^\n]*\n','',js,flags=re.M)
js=js.replace("go('瞬间');",(root/'refine.js').read_text(encoding='utf-8')+"\ngo('瞬间');")
(root/'app.js').write_text(js,encoding='utf-8')
css=(root/'style.css').read_text(encoding='utf-8')+'\n'+(root/'refine.css').read_text(encoding='utf-8')
(root/'style.css').write_text(css,encoding='utf-8')
html=(root/'index.html').read_text(encoding='utf-8')
(root/'YOU-prototype.html').write_text(html.replace('<link rel="stylesheet" href="style.css">','<style>'+css+'</style>').replace('<script src="app.js"></script>','<script>'+js+'</script>'),encoding='utf-8')
print('Updated source and standalone prototype.')
