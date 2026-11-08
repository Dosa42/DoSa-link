import argparse
import base64
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('decoded', type=Path)
    args = parser.parse_args()
    root = args.decoded
    inventory = json.loads((root / 'page-inventory.json').read_text())
    analysis = json.loads((root / 'tft-analysis.json').read_text())
    image_report = json.loads((root / 'pictures/pictures.json').read_text())
    pages = []
    offsets = set()
    for page in inventory['pages']:
        objects = []
        offsets.update(page['initBlockOffsets'])
        for obj in page['objects']:
            for event in obj['events']:
                offsets.update(event['blockOffsets'])
            objects.append({k: v for k, v in obj.items() if k != 'attributes'} | {'attributes': {k: v.get('value', v.get('valueHex')) for k, v in obj['attributes'].items()}})
        pages.append({k: v for k, v in page.items() if k != 'objects'} | {'objects': objects})
    code = {str(x['offset']): x.get('decodedCandidate', x.get('decodeError', '')) for x in analysis['bytecode']['blocks'] if x['offset'] in offsets}
    images = {str(x['id']): 'data:image/png;base64,' + base64.b64encode((root / 'pictures' / x['file']).read_bytes()).decode() for x in image_report['pictures']}
    data = {'pages': pages, 'code': code, 'images': images, 'pictures': [{k: x[k] for k in ['id', 'width', 'height']} for x in image_report['pictures']], 'sourceSha256': analysis['sha256']}
    template = r'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>NSPanel recovered screen</title>
<style>
:root{color-scheme:dark;font:15px system-ui;background:#10141c;color:#e7ecf5}body{margin:0}header{padding:24px;background:#192231}h1{margin:0 0 8px;font-size:25px}p{line-height:1.5;max-width:1000px}.controls{display:flex;gap:12px;flex-wrap:wrap;align-items:center}button,select,input{font:inherit;color:inherit;background:#263549;border:1px solid #526682;padding:9px;border-radius:6px}button{cursor:pointer}.workspace{display:grid;grid-template-columns:minmax(320px,1fr) minmax(300px,1fr);gap:20px;padding:20px}.stage{position:relative;background:black;aspect-ratio:3/2;max-width:800px;overflow:hidden}.stage img{position:absolute;object-fit:fill}.zone{position:absolute;border:1px solid #56e0c588;box-sizing:border-box;cursor:pointer;background:transparent;min-width:2px;min-height:2px;padding:0}.zone:hover,.zone.selected{background:#56e0c533;border-color:#56e0c5}.text{position:absolute;white-space:pre;overflow:hidden;font:14px sans-serif;pointer-events:none}.card{background:#192231;padding:16px;border-radius:10px;margin-bottom:16px}.list{max-height:330px;overflow:auto}.list button{display:block;width:100%;text-align:left;margin-bottom:5px}.muted{color:#b3c2d7;font-size:13px}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:12px ui-monospace,monospace;max-height:540px;overflow:auto}table{border-collapse:collapse;width:100%}td{padding:5px;border-bottom:1px solid #334156;overflow-wrap:anywhere}.gallery{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px;padding:20px}.picture{background:#192231;padding:10px;text-align:center}.picture img{width:100%;height:110px;object-fit:contain;background:black;cursor:pointer}.picture p{margin:5px;font-size:13px}dialog{max-width:95vw;background:#192231;color:inherit;border:1px solid #7189ad}dialog img{max-width:85vw;max-height:75vh;object-fit:contain;background:black}summary{cursor:pointer;padding:8px;color:#75e4cd}#details{min-height:200px}@media(max-width:850px){.workspace{grid-template-columns:1fr}header{padding:16px}}
</style>
<header><h1>NSPanel recovered screen</h1><p>359 recovered pictures · 28 pages · 1,058 objects · 1,785 event chains · 10,132 attributes</p><p class="muted">Static recovered layout in its 480 × 320 logical coordinates. Timers, visibility changes, scripts, original font rasterization and physical display rotation are not simulated. Names shown are hash-matched candidates; event and opcode names use the compatible editor-family interpretation.</p><div class="controls"><select id="page"></select><label><input id="outlines" type="checkbox" checked> Object outlines</label><button id="showInit">Page initialization code</button><a href="#gallery">Picture gallery</a></div></header>
<div class="workspace"><section><div id="stage" class="stage"></div><p id="pageInfo" class="muted"></p><div class="card"><h3>Objects</h3><div id="objects" class="list"></div></div></section><section id="details" class="card"><p>Select an object to inspect recovered attributes and event code.</p></section></div>
<header id="gallery"><h2>Recovered pictures</h2><div class="controls"><input id="filter" placeholder="Picture ID, width or height"><span class="muted">RGB565 colors recovered; no alpha or device blending behavior asserted.</span></div></header><div id="pictures" class="gallery"></div><dialog id="dialog"><button id="close">Close</button><p id="caption"></p><img id="large"></dialog>
<script id="data" type="application/json">__DATA__</script><script>
const data=JSON.parse(document.getElementById('data').textContent);
const el=id=>document.getElementById(id);
const hex=value=>'0x'+value.toString(16);
const label=obj=>obj.nameCandidates.length?obj.nameCandidates.join(' / '):'object '+obj.id;
const rgb565=value=>'rgb('+((value>>11&31)*255/31)+','+((value>>5&63)*255/63)+','+((value&31)*255/31)+')';
function node(tag,text,parent){const item=document.createElement(tag);if(text!==undefined)item.textContent=text;if(parent)parent.append(item);return item;}
function showCode(parent,title,offsets){const group=node('details',undefined,parent);node('summary',title+' · '+offsets.length+' blocks',group);const lines=offsets.map(offset=>hex(offset)+'  '+(typeof data.code[offset]==='string'?data.code[offset]:JSON.stringify(data.code[offset])));node('pre',lines.join('\n'),group);}
function inspect(obj){const panel=el('details');panel.replaceChildren();node('h2',label(obj)+' · ID '+obj.id,panel);node('p','Type '+obj.type+' · '+obj.width+' × '+obj.height+' at '+obj.x+', '+obj.y,panel);node('p','Object record '+hex(obj.recordOffset)+' · name hash '+obj.nameHash,panel).className='muted';const table=node('table',undefined,panel);for(const [name,value] of Object.entries(obj.attributes)){const row=node('tr',undefined,table);node('td',name,row);node('td',String(value),row);}node('h3','Events',panel);for(const event of obj.events)showCode(panel,event.kindCandidate+' · '+hex(event.offset),event.blockOffsets);for(const item of el('stage').querySelectorAll('.zone'))item.classList.toggle('selected',Number(item.dataset.id)===obj.id);}
function draw(){const page=data.pages[Number(el('page').value)];const stage=el('stage');stage.replaceChildren();stage.style.aspectRatio=page.width+'/'+page.height;const a=page.objects[0].attributes;if(a.sta===1)stage.style.background=rgb565(a.bco??0);else stage.style.background='black';function image(id,x,y,w,h){if(!data.images[id])return;const img=node('img',undefined,stage);img.src=data.images[id];Object.assign(img.style,{left:(x/page.width*100)+'%',top:(y/page.height*100)+'%',width:(w/page.width*100)+'%',height:(h/page.height*100)+'%'});}if(a.sta===2)image(a.pic??a.bco,0,0,page.width,page.height);for(const obj of page.objects){if(obj.id===0)continue;const a=obj.attributes;if(obj.type===112)image(a.pic,obj.x,obj.y,obj.width,obj.height);if(a.txt){const text=node('span',String(a.txt),stage);text.className='text';Object.assign(text.style,{left:(obj.x/page.width*100)+'%',top:(obj.y/page.height*100)+'%',width:(obj.width/page.width*100)+'%',height:(obj.height/page.height*100)+'%',color:rgb565(a.pco??65535)});}}const list=el('objects');list.replaceChildren();for(const obj of page.objects){const button=node('button',obj.id+' · '+label(obj)+' · type '+obj.type,list);button.onclick=()=>inspect(obj);if(el('outlines').checked&&obj.width>1&&obj.height>1){const zone=node('button',undefined,stage);zone.className='zone';zone.dataset.id=obj.id;zone.title=label(obj);Object.assign(zone.style,{left:(obj.x/page.width*100)+'%',top:(obj.y/page.height*100)+'%',width:(obj.width/page.width*100)+'%',height:(obj.height/page.height*100)+'%'});zone.onclick=()=>inspect(obj);}}el('pageInfo').textContent='Page '+page.id+' · '+(page.nameCandidates.join(' / ')||'name unresolved')+' · '+page.objects.length+' objects · frame '+hex(page.frameOffset);inspect(page.objects[0]);}
for(const page of data.pages){const item=node('option','Page '+page.id+' · '+(page.nameCandidates.join(' / ')||'name unresolved'),el('page'));item.value=page.id;}
el('page').onchange=draw;el('outlines').onchange=draw;el('showInit').onclick=()=>{const page=data.pages[Number(el('page').value)];el('details').replaceChildren();node('h2','Page '+page.id+' initialization',el('details'));showCode(el('details'),'Initialization',page.initBlockOffsets);};
function gallery(){const filter=el('filter').value.trim();el('pictures').replaceChildren();for(const pic of data.pictures){if(filter&&!String(pic.id).includes(filter)&&!String(pic.width).includes(filter)&&!String(pic.height).includes(filter))continue;const card=node('div',undefined,el('pictures'));card.className='picture';const img=node('img',undefined,card);img.src=data.images[pic.id];img.loading='lazy';node('p','ID '+pic.id+' · '+pic.width+' × '+pic.height,card);img.onclick=()=>{el('large').src=data.images[pic.id];el('caption').textContent='Picture '+pic.id+' · '+pic.width+' × '+pic.height;el('dialog').showModal();};}}
el('filter').oninput=gallery;el('close').onclick=()=>el('dialog').close();draw();gallery();
</script></html>'''
    serialized = json.dumps(data, separators=(',', ':')).replace('<', '\\u003c')
    target = root / 'screen-preview.html'
    target.write_text(template.replace('__DATA__', serialized))
    print(json.dumps({'path': str(target), 'bytes': target.stat().st_size, 'selfContained': True, 'pictures': len(images), 'eventCodeBlocks': len(code)}))


if __name__ == '__main__':
    main()
