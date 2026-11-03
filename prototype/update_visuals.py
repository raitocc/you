from pathlib import Path
import urllib.request, concurrent.futures, json, re

root = Path(__file__).parent
names = dict(grid='grid-view', clock='time-02', people='user-multiple', circle='user-group', plus='add-01', search='search-01', back='arrow-left-01', heart='favourite', comment='bubble-chat', share='share-01', edit='edit-02', image='image-02', text='text-font', arrow='arrow-right-01', more='more-horizontal', lock='lock-key', close='cancel-01', sparkles='sparkles', signal='cellular-network', wifi='wifi-01', battery='battery-full')
assets = root / 'assets' / 'hugeicons'
assets.mkdir(parents=True, exist_ok=True)
def fetch(item):
    key, name = item
    svg = urllib.request.urlopen(f'https://raw.githubusercontent.com/hugeicons/hugeicons-static/main/icons/{name}.svg', timeout=30).read().decode()
    svg = svg.replace('stroke="#141B34"', 'stroke="currentColor"')
    svg = svg.replace('<svg ', '<svg aria-hidden="true" focusable="false" data-icon="'+name+'" ', 1)
    (assets / (name+'.svg')).write_text(svg, encoding='utf-8')
    return key, svg
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    icons = dict(pool.map(fetch, names.items()))
js = (root / 'app.js').read_text(encoding='utf-8')
start, end = js.index('const paths='), js.index('const ib=')
js = js[:start] + '// Hugeicons Free · Stroke Rounded — https://github.com/hugeicons/hugeicons-static\nconst icons=' + json.dumps(icons, ensure_ascii=False) + ';\nconst icon=n=>icons[n]||icons.circle;\n' + js[end:]
js = js.replace('✧ YOU 的时光手记', "${icon('sparkles')} YOU 的时光手记")
js = js.replace(' &nbsp; ↗', " &nbsp; ${icon('arrow')}")
(root / 'app.js').write_text(js, encoding='utf-8')
css = (root / 'style.css').read_text(encoding='utf-8')
css = css.replace('-apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif', '"SF Pro","SF Pro Text","SF Pro Display","PingFang SC",-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif')
css = css.replace('font-family:Georgia,"Songti SC",serif', 'font-family:inherit').replace('font-family:Georgia,serif', 'font-family:inherit')
css += '\n/* Shared Hugeicons mobile sizing; fonts use locally installed Apple faces. */\nsvg{flex-shrink:0;stroke-width:1.5}.status-icons{display:flex;align-items:center;gap:5px}.status-icons svg{width:15px;height:15px}.status-icons svg:last-child{width:23px}.ai b svg{width:17px;height:17px;margin-right:4px}.feature-text>span>svg{width:16px;height:16px}.intro h1{font-weight:400}\n'
(root / 'style.css').write_text(css, encoding='utf-8')
html = (root / 'index.html').read_text(encoding='utf-8')
html = html.replace('<span>▮▮▮ &nbsp; Wi-Fi &nbsp; ▰</span>', '<span class="status-icons" aria-label="信号、无线网络、电池">'+icons['signal']+icons['wifi']+icons['battery']+'</span>')
(root / 'index.html').write_text(html, encoding='utf-8')
bundle = html.replace('<link rel="stylesheet" href="style.css">', '<style>'+css+'</style>').replace('<script src="app.js"></script>', '<script>'+js+'</script>')
(root / 'YOU-prototype.html').write_text(bundle, encoding='utf-8')
print(f'Updated {len(icons)} official Hugeicons, fonts, source and standalone HTML.')
