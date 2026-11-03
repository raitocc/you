import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url));
const names=['AiArtIcon','AiBookIcon','AiBackgroundIcon','AppleIntelligenceIcon'];
const data=await Promise.all(names.map(async name=>{
 const response=await fetch(`https://unpkg.com/@hugeicons/core-free-icons@latest/dist/esm/${name}.js`);
 if(!response.ok)throw Error(`${name}: ${response.status}`);
 const source=await response.text();
 const mod=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
 return mod.default;
}));
const kebab=s=>s.replace(/[A-Z]/g,c=>'-'+c.toLowerCase());
const elements=items=>items.map(([tag,attrs])=>`<${tag} ${Object.entries(attrs).filter(([k])=>k!=='key').map(([k,v])=>`${kebab(k)}="${v}"`).join(' ')}/>`).join('');
const svg=(name,variant,body)=>`<svg viewBox="0 0 24 24" fill="none" aria-hidden="true" focusable="false" data-icon="${name}" data-variant="${variant}">${body}</svg>`;
const p=(d,fill='currentColor',stroke='none',width=0)=>`<path d="${d}" fill="${fill}" stroke="${stroke}" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round"/>`;
const filled=[
 p(data[0][0][1].d+'Z')+elements([data[0][1]])+p(data[0][2][1].d),
 p(data[1][0][1].d)+elements(data[1].slice(1,2))+elements(data[1].slice(2).map(([tag,a])=>[tag,{...a,stroke:'var(--tab-cutout)'}])),
 p(data[2][0][1].d+' L13.5 4Z')+elements(data[2].slice(1,4).map(([tag,a])=>[tag,{...a,stroke:'var(--tab-cutout)'}]))+p(data[2][4][1].d),
 p('M20.465 15.2776C21.5834 14.9184 21.8652 13.4928 20.9647 12.749L18.5414 10.7472L19.657 7.81789C20.0649 6.74666 19.1288 5.64722 17.9828 5.85166L14.7505 6.42828L12.998 3.68971C12.3882 2.73674 10.9595 2.7795 10.4099 3.76716L8.85203 6.56678L5.55954 6.17312C4.40146 6.03466 3.53226 7.18955 4.00706 8.23589L5.2878 11.0584L2.96854 13.22C2.11569 14.0149 2.48271 15.4203 3.61989 15.7141L6.74168 16.5208L7.07263 19.6754C7.19058 20.7997 8.50033 21.3805 9.43738 20.7242L12.1229 18.8432L14.942 20.5819C15.9184 21.1841 17.193 20.5254 17.2401 19.3942L17.3702 16.2713Z')+p('M8.852 6.567L6.742 16.521L18.541 10.747L8.852 6.567L17.37 16.271L14.75 6.428L5.288 11.058L12.123 18.843L14.75 6.428','none','var(--tab-cutout)',.85)
];
const tabIcons=Object.fromEntries(names.map((n,i)=>[n,{outline:svg(n,'outline',elements(data[i])),filled:svg(n,'filled',filled[i])}]));
for(const [name,variants] of Object.entries(tabIcons))for(const [variant,source]of Object.entries(variants))await fs.writeFile(path.join(root,'assets/hugeicons',`${name}-${variant}.svg`),source);
let js=await fs.readFile(path.join(root,'app.js'),'utf8');
const block=`// Official Hugeicons outlines; filled variants adapted locally for this prototype.\nconst tabIcons=${JSON.stringify(tabIcons)};\nconst tabIcon=(name,active)=>tabIcons[name][active?'filled':'outline'];\n`;
const prior=js.indexOf('// Official Hugeicons outlines;');
if(prior!==-1)js=js.slice(0,prior)+js.slice(js.indexOf('function nav()',prior));
const start=js.indexOf('function nav()');
const end=js.indexOf('\nfunction go(',start);
js=js.slice(0,start)+block+`function nav(){ $('#nav').style.display='flex';$('#nav').innerHTML=['瞬间','时光','创建','同行','圈子'].map((n,i)=>\`<button aria-label="\${n}" aria-current="\${n===tab?'page':'false'}" class="\${n===tab?'active ':''}\${i===2?'create':''}" onclick="\${i===2?'createSheet()':\`go('\${n}')\`}">\${i===2?icon('plus'):tabIcon(['AiArtIcon','AiBookIcon',null,'AiBackgroundIcon','AppleIntelligenceIcon'][i],n===tab)}\${i!==2?\`<span>\${n}</span>\`:''}</button>\`).join('')}\n`+js.slice(end);
await fs.writeFile(path.join(root,'app.js'),js);
let css=await fs.readFile(path.join(root,'style.css'),'utf8');
css+='\nnav{--tab-cutout:#f0f2ec}nav svg[data-variant]{width:24px;height:24px}nav svg[data-variant="filled"]{stroke:none}\n';
await fs.writeFile(path.join(root,'style.css'),css);
const html=await fs.readFile(path.join(root,'index.html'),'utf8');
await fs.writeFile(path.join(root,'YOU-prototype.html'),html.replace('<link rel="stylesheet" href="style.css">','<style>'+css+'</style>').replace('<script src="app.js"></script>','<script>'+js+'</script>'));
console.log('Updated four Tab icons with Outline/Filled states and aria-current.');
