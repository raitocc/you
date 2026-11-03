from pathlib import Path
import re
root=Path(__file__).parent
js=(root/'app.js').read_text(encoding='utf-8')
# Remove an accidentally duplicated refinement block nested in publish.
a=js.index('save();// Interaction and visual refinements.');b=js.index("go('瞬间');toast('瞬间已保存，时光集已更新')}",a)
js=js[:a]+'save();'+js[b:]
js=js.replace("function moodBadge(mood,label='心情')",'function moodBadge(mood,label=mood)')
js=js.replace("${m.people?'一起的人 · '+esc(m.people):'一个人的片刻'}", "${m.people?participantFaces(m.people):'一个人的片刻'}")
# Add a separate sticky search/filter region beneath the sticky brand header.
js=js.replace('app.innerHTML=header()+`<div class="search">','app.innerHTML=header()+`<div class="moment-tools"><div class="search">')
js=js.replace(".join('')}</div><div class=\"section-label\"><b>生活的切片", ".join('')}</div></div><div class=\"section-label\"><b>生活的切片")
# Only moment detail keeps immersive photo navigation.
for name in ['tripDetail','timeDetail']:
    pattern=r'^function '+name+r'\([^\n]*'
    js=re.sub(pattern,lambda m:m[0].replace('immersive();app.scrollTop=0','app.scrollTop=0'),js,flags=re.M)
js=js.replace("${circles.length?post({id:100,title:'今天的风，刚刚好',text:'把忙碌留在城市，把今天留在这里。下次一起。',images:[imgs[5]],date:'2026-10-26',place:'湖边'},'小满'):","${circles.length?communityExamples.map(m=>post(m,m.author)).join(''):")
new=(root/'polish.js').read_text(encoding='utf-8')
names=re.findall(r'^function (\w+)\(',new,re.M)
for name in names:js=re.sub(r'^function '+name+r'\([^\n]*\n','',js,flags=re.M)
idx=js.rindex("go('瞬间');")
js=js[:idx]+new+'\n'+js[idx:]
(root/'app.js').write_text(js,encoding='utf-8')
css=(root/'style.css').read_text(encoding='utf-8')+'\n'+(root/'polish.css').read_text(encoding='utf-8')
(root/'style.css').write_text(css,encoding='utf-8')
html=(root/'index.html').read_text(encoding='utf-8')
(root/'YOU-prototype.html').write_text(html.replace('<link rel="stylesheet" href="style.css">','<style>'+css+'</style>').replace('<script src="app.js"></script>','<script>'+js+'</script>'),encoding='utf-8')
