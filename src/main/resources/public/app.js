import { renderFarm, drawMascots, drawTowerIcon } from './art.js';
import { createCamera, centerCamera, constrainCamera, screenToWorld, panCamera } from './camera.js';

const $ = id => document.getElementById(id);
const visible = (id, on) => $(id).classList.toggle('hidden', !on);
const text = (id, value) => { if ($(id).textContent !== String(value)) $(id).textContent = value; };
const el = (tag, value, cls) => { const n = document.createElement(tag); if (value != null) n.textContent = value; if (cls) n.className = cls; return n; };
const store = { get: k => { try { return sessionStorage.getItem(k); } catch { return null; } }, set: (k,v) => { try { if(v==null) sessionStorage.removeItem(k); else sessionStorage.setItem(k,v); } catch {} } };
let config, state = null, token = store.get('pumpkin-token'), myId, stream, selected = null, skin = 'pumpkin', busy = false, connected = false;
let lastPhase = '', rosterKey = '', charmKey = '', resultShown = '', toastTimer, inputBusy = false, audio, sound = false;
const keys = new Set(), touch = new Set(); let repairHeld = false;
const camera=createCamera();let drag=null;let touchPan=false;
const active = () => state && ['dusk','wave','vote'].includes(state.phase);
const me = () => state?.players.find(p => p.id === myId);
const fmt = n => { const s = String(n); if(s.length > 15) return `${s[0]}.${s.slice(1,3)}e${s.length-1}`; return BigInt(s).toLocaleString(); };
function toast(message) { text('toast', message); $('toast').classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').classList.remove('show'), 3800); }
async function api(path, data = {}) {
  const response = await fetch(`/api/${path}`, { method: 'POST', headers: { 'Content-Type':'application/json', ...(token ? { Authorization:`Bearer ${token}` } : {}) }, body:JSON.stringify(data), signal:AbortSignal.timeout(6000) });
  const result = await response.json(); if(!response.ok) { const error = new Error(result.error || 'The patch could not be reached.'); error.status = response.status; throw error; } return result;
}
async function command(data, quiet = false) { if(!connected) { if(!quiet) toast('Waiting for your connection to the patch.'); return false; } try { await api('action', data); return true; } catch(e) { if(!quiet) toast(e.message); return false; } }
function chime(freq=440) { if(!sound || !audio) return; const o=audio.createOscillator(),g=audio.createGain(); o.type='sine';o.frequency.value=freq;g.gain.setValueAtTime(.045,audio.currentTime);g.gain.exponentialRampToValueAtTime(.001,audio.currentTime+.25);o.connect(g);g.connect(audio.destination);o.start();o.stop(audio.currentTime+.26); }
function resetControls() { keys.clear();touch.clear();repairHeld=false;drag=null;$("farm").classList.remove("dragging"); }
function showDialog(id) { resetControls(); if(!$(id).open){if($(id).closest('.game-stage'))$(id).show();else $(id).showModal();} }
function enter(result) {
  token=result.token;myId=result.id;store.set('pumpkin-token',token);lastPhase='';resultShown='';rosterKey='';charmKey='';selected=null;state=result.state;centerCamera(camera,me());renderUI();
  stream?.close();stream=new EventSource(`/api/events?token=${encodeURIComponent(token)}`);
  stream.onopen=()=>{connected=true;visible('connection',false);};
  stream.onmessage=e=>{ connected=true;visible('connection',false);state=JSON.parse(e.data);renderUI(); };
  stream.onerror=async()=>{connected=false;resetControls();visible('connection',true);try{await api('resume');}catch(e){if(e.status===401){clearRoom();toast(e.message);}}};
  $('farm').focus({preventScroll:true});
}
function clearRoom() { stream?.close();stream=null;token=null;store.set('pumpkin-token',null);state=null;connected=false;selected=null;lastPhase='';resetControls();for(const d of document.querySelectorAll('dialog'))d.close();visible('connection',false);renderUI(); }
async function join(practice=false, code=null) {
  if(busy)return;busy=true;for(const id of ['create','practice'])$(id).disabled=true;
  try { const result=await api(code?'join':'create',{ name:$('name').value,skin,practice,...(code?{code}:{}) });store.set('pumpkin-name',$('name').value);enter(result);chime(523); }
  catch(e){toast(e.message);}finally{busy=false;for(const id of ['create','practice'])$(id).disabled=false;}
}
function renderRoster() {
  const key=JSON.stringify(state.players.map(p=>[p.id,p.name,p.skin,p.ready,p.connected]))+state.host+state.phase;
  if(key===rosterKey)return;rosterKey=key;$('roster').replaceChildren();
  for(const p of state.players){const row=el('div',null,'roster-player');row.append(el('span',p.skin==='pumpkin'?'🎃':'🌾','roster-avatar'));const name=el('div',p.name,'roster-name');name.append(el('small',`${p.id===myId?'YOU · ':''}${p.id===state.host?'PATCH HOST':'NIGHT CREW'}`));row.append(name,el('span',!p.connected?'away':state.phase==='lobby'?(p.ready?'✓ ready':'getting cozy'):'on watch','player-status'));$('roster').append(row);}
}
function renderCharms() {
  const key=JSON.stringify(state?.charms || {});if(key===charmKey)return;charmKey=key;
  const owned=Object.entries(state?.charms || {});text('charm-count',owned.reduce((sum,[,count])=>sum+count,0));$('charms').replaceChildren();
  if(!owned.length){const teaser=el('div',null,'charm-teaser');teaser.append(el('span','×'));const words=el('div');words.append(el('strong','Better by the dozen'),el('small','Finish a wave to choose your first charm.'));teaser.append(words,el('i','✧'));$('charms').append(teaser);return;}
  for(const [id,count] of owned){const c=config.charms.find(c=>c.id===id),row=el('div',null,'owned-charm');row.title=c.description;row.append(el('span',c.icon));const words=el('div');words.append(el('strong',c.name),el('small',c.description));row.append(words,el('b',`×${count}`));$('charms').append(row);}
}
function renderSelection() {
  const plot=state?.plots.find(p=>p.id===selected), player=me(), near=plot&&player&&Math.hypot(plot.x-player.x,plot.y-player.y)<=88;
  const can=active()&&connected&&player?.ghost<=0;
  for(const button of $('build-grid').children){const spec=config.types[button.dataset.type];button.disabled=!!state&&(!can||!plot||!!plot.tower||!near||state.seeds<spec.cost);button.title=!state?'Join a patch to plant defenses.':!plot?'Tap a garden plot first.':!near?'Walking closer to this plot…':`${spec.name} · ${spec.cost} moonseeds`;}
  visible('plot-actions',!!plot?.tower&&active());
  if(plot?.tower){const t=plot.tower,cost=Math.ceil(config.types[t.type].cost*.75*1.55**(t.level-1));text('plot-detail',`${config.types[t.type].name} · Lv ${t.level} · ${Math.ceil(t.hp)} / ${Math.ceil(t.maxHp)} HP`);text('upgrade',t.level>=10?'Max level':`Upgrade · ◈ ${cost}`);$('upgrade').disabled=!can||!near||state.seeds<cost||t.level>=10;$('repair').disabled=!can||!near||t.hp>=t.maxHp;}
  text('build-hint',!active()?'Four little defenses. A whole lot of possibility.':!plot?'Tap a marked plot. Walk close, then choose a defense.':!near?'On your way to the plot…':plot.tower?'Upgrade its power or hold repair to restore its health.':'Cannons spray outward across the highlighted semicircle.');
}
function renderVote(newPhase) {
  if(newPhase){$('vote-options').replaceChildren();for(const c of state.options){const button=el('button',null,'vote-card');button.dataset.charm=c.id;button.style.setProperty('--charm-color',c.color);button.append(el('span',c.icon),el('small',c.tag),el('h3',c.name),el('p',c.description),el('b',''));button.onclick=()=>command({type:'vote',charm:c.id});$('vote-options').append(button);}showDialog('vote-dialog');}
  for(const button of $('vote-options').children){const cid=button.dataset.charm,count=Object.values(state.votes).filter(v=>v===cid).length;button.classList.toggle('chosen',state.votes[myId]===cid);button.querySelector('b').textContent=`${count} vote${count===1?'':'s'}${state.votes[myId]===cid?' · your pick':''}`;}
  text('vote-timer',`${Math.ceil(state.time)}s to choose · majority wins`);
}
function showResults() {
  const won=state.phase==='won';text('result-icon',won?'☀':'☾');text('result-eyebrow',won?'YOU MADE IT TO MORNING':'THE FARMER IS AWAKE');text('result-title',won?'Sweet dreams, farmer.':'Still a lovely little watch.');text('result-description',won?'The skeletons are gone. Your farmer slept through it all.':'Gather your crew, try a new combination, and give the night another go.');text('result-score',fmt(state.score));$('contributions').replaceChildren();
  for(const p of state.players){const row=el('div',null,'result-contribution');row.append(el('strong',p.name),el('span',`${Math.round(p.repair)} repaired · ${p.collected} seeds`));$('contributions').append(row);}
  $('rematch').disabled=state.host!==myId;text('rematch',state.host===myId?'One more night →':'Waiting for the host’s next night');showDialog('result-dialog');chime(won?659:220);
}
function renderUI() {
  document.body.classList.toggle('playing',!!active());
  visible('welcome',!state);visible('room-panel',!!state);visible('touch-controls',!!active());visible('solo-tools',!!state?.practice);visible('mission',!!state&&state.phase!=='lobby');
  text('seeds',state?fmt(state.seeds):'—');text('score',fmt(state?.score||'0'));const sleep=Math.max(0,100-(state?.wake||0));text('sleep-number',`${Math.ceil(sleep)}%`);$('sleep-bar').style.width=`${sleep}%`;$('sleep-bar').style.background=sleep<30?'#e79c83':'var(--green)';renderCharms();
  if(!state){text('phase-label','THE PATCH IS QUIET');text('clock','☾ Dusk is approaching');text('scene-caption','✦ YOUR LITTLE CORNER OF THE NIGHT');text('wave-title','DUSK');for(const dot of $('wave-dots').children)dot.className='';renderSelection();return;}
  renderRoster();const p=me(),lobby=state.phase==='lobby',host=state.host===myId;const changed=lastPhase!==state.phase;
  text('code',state.code);text('player-count',state.practice?'JUST YOU':`${state.players.filter(p=>p.connected).length} / 8`);document.querySelector('.room-code-line').classList.toggle('hidden',state.practice);visible('room-invite',!state.practice);visible('solo-tools',state.practice);visible('begin-night',state.practice&&state.phase==='dusk');visible('restart-solo',state.practice&&active());text('room-invite','Friends: open the server’s Wi-Fi address, then enter this code.');
  visible('ready',lobby);visible('start',lobby&&host);visible('lobby-note',lobby);visible('mission',!lobby);text('ready',p?.ready?'✓ Ready! Click to unready':"I'm ready ✦");
  const present=state.players.filter(p=>p.connected);$('start').disabled=present.length<(state.practice?1:2)||!present.every(p=>p.ready);text('lobby-note',!host?'Your host starts the watch once everyone is ready.':state.practice?'A quiet place to learn. Ready up to start.':'At least 2 players. Everyone must be ready.');
  const labels={lobby:'GATHER YOUR NIGHT CREW',dusk:'DUSK · TIME TO PLANT',wave:`WAVE ${state.wave} · HOLD THE PATCH`,vote:'HARVEST · PICK A CHARM',won:'DAWN · A BEAUTIFUL LITTLE VICTORY',lost:'THE FARMER WOKE UP'};
  text('phase-label',labels[state.phase]);text('clock',state.phase==='wave'&&state.time===0?`${state.enemies.length} visitors left`:active()?`${Math.floor(Math.ceil(state.time)/60)}:${String(Math.ceil(state.time)%60).padStart(2,'0')} ${state.phase==='dusk'?'until nightfall':state.phase==='vote'?'to choose':'until cleanup'}`:'☾ '+(state.practice?'Practice patch':'Together until morning'));
  text('wave-title',state.wave?`WAVE ${state.wave} / 5`:'DUSK');[...$('wave-dots').children].forEach((dot,i)=>dot.className=i<state.wave-1||state.phase==='won'?'done':i===state.wave-1?'current':'');
  text('scene-caption',p?.ghost>0?`A LITTLE GHOST · BACK IN ${Math.ceil(p.ghost)}s`:state.phase==='lobby'?'✦ PREVIEW · YOUR FRESH PATCH AWAITS':state.notice);
  text('mission-title',state.phase==='vote'?'Choose your next charm.':state.phase==='dusk'?'Plant a little courage.':state.phase==='won'?'You kept the dream alive.':state.phase==='lost'?'A new night awaits.':'Protect the patch.');
  text('mission-text',state.phase==='dusk'?'Enemies arrive from all sides. Select a ring plot to see its outward firing arc. Gather seeds around the farmhouse.':state.phase==='vote'?'The next wave is stronger. Choose a charm, gather seeds, and repair your defenses.':'Gather glowing seeds. Upgrade your towers. Hold E near a damaged defense to repair it.');
  text('health',p?.ghost>0?`Returning in ${Math.ceil(p.ghost)}s`:`${Math.max(0,Math.ceil(p?.hp||0))} / 100`);$('health-bar').style.width=`${Math.max(0,p?.hp||0)}%`;
  visible('reopen-vote',state.phase==='vote');
  if(state.phase==='vote')renderVote(changed);else $('vote-dialog').close();
  if(state.phase==='dusk'&&$('result-dialog').open){$('result-dialog').close();resultShown='';}
  if(changed){resetControls();if(state.phase==='dusk'){selected=null;toast('Your watch begins. Cover every side of the farmhouse.');}if(state.phase==='wave')chime(330);if(state.phase==='lobby'){$('result-dialog').close();resultShown='';}lastPhase=state.phase;}
  if(['won','lost'].includes(state.phase)&&resultShown!==state.phase){resultShown=state.phase;showResults();}
  if($('result-dialog').open){$('rematch').disabled=!host;text('rematch',host?'One more night →':'Waiting for the host’s next night');}
  renderSelection();
}
function makeCards() {
  const descriptions={cannon:'Nine shots. A 180° outward fan.',lantern:'Quick sparks. Better with friends.',fence:'A thorny little line of defense.',flower:'Grow seeds for bigger dreams.'};
  Object.entries(config.types).forEach(([type,spec],i)=>{const button=el('button',null,'build-card');button.dataset.type=type;button.append(el('span',i+1,'key'));const canvas=el('canvas');canvas.width=116;canvas.height=100;button.append(canvas,el('strong',spec.name),el('p',descriptions[type]),el('span',`◈ ${spec.cost}`,'cost'),el('span','+','build-arrow'));button.onclick=async()=>{if(!state){toast('Create or join a patch to plant.');$('name').focus();return;}if(selected==null){toast('Tap a marked garden plot first.');return;}if(await command({type:'build',plot:selected,build:type}))chime(523);};$('build-grid').append(button);drawTowerIcon(canvas,type);});
}
function setupGameWindow(){
 const frame=document.querySelector('.farm-frame'),stage=el('div',null,'game-stage');stage.id='game-stage';frame.insertBefore(stage,$('farm'));
 stage.append($('farm'),$('scene-caption'),$('connection'),$('toast'));
 const shop=el('section',null,'plant-hud');shop.id='plant-hud';shop.setAttribute('aria-label','Plant shop');shop.append(document.querySelector('.build-heading'),$('build-grid'),$('plot-actions'));stage.append(shop);
 document.querySelector('.build-heading h2').textContent='Plant your defenses';document.querySelector('.section-number')?.remove();
 const toolbar=el('div',null,'game-toolbar');toolbar.innerHTML='<span id="camera-mode">Following you</span><button id="pan-mode" class="hud-button" aria-label="Toggle touch camera panning" aria-pressed="false">Pan</button><button id="recenter" class="hud-button" title="Recenter on your character (Space)" aria-label="Recenter camera">&#8982; <span>Space</span></button><button id="open-charms" class="hud-button" aria-label="Open charms" aria-expanded="false">&#10023; Charms <b id="charm-count">0</b></button>';
 toolbar.append($('sound'),$('fullscreen'));stage.append(toolbar,$('mission'),$('solo-tools'),$('touch-controls'));
 const charms=document.querySelector('.charms-panel');charms.id='charm-menu';charms.classList.add('hidden');charms.setAttribute('role','region');charms.setAttribute('aria-label','Your charms');const close=el('button','×','menu-close');close.id='close-charms';close.setAttribute('aria-label','Close charms');charms.prepend(close);stage.append(charms);
 for(const id of ['vote-dialog','result-dialog'])stage.append($(id));
 const note=document.querySelector('.control-note');note.innerHTML='<span><kbd>WASD</kbd> move <i>·</i> <kbd>E</kbd> repair <i>·</i> Right-drag to pan <i>·</i> <kbd>Space</kbd> recenter</span><span class="tap-hint">Tap to walk · Pan to drag · &#8982; to recenter</span>';
 document.querySelector('.page-heading')?.remove();document.querySelector('.cozy-note')?.remove();document.querySelector('footer')?.remove();
 $('recenter').onclick=()=>centerCamera(camera,me());$('pan-mode').onclick=()=>{touchPan=!touchPan;$('pan-mode').setAttribute('aria-pressed',String(touchPan));};
 const toggleCharms=on=>{visible('charm-menu',on);$('open-charms').setAttribute('aria-expanded',String(on));};
 $('open-charms').onclick=()=>toggleCharms($('charm-menu').classList.contains('hidden'));$('close-charms').onclick=()=>toggleCharms(false);
 window.addEventListener('keydown',e=>{if(e.key==='Escape')toggleCharms(false);});
}
setupGameWindow();
$('create').onclick=()=>join();$('practice').onclick=()=>join(true);$('join-form').onsubmit=e=>{e.preventDefault();join(false,$('room-code').value.trim().toUpperCase());};
$('name').value=store.get('pumpkin-name')||'';
document.querySelectorAll('.skin').forEach(b=>b.onclick=()=>{skin=b.dataset.skin;document.querySelectorAll('.skin').forEach(q=>{q.classList.toggle('active',q===b);q.setAttribute('aria-pressed',String(q===b));});});
$('begin-night').onclick=()=>command({type:'begin-night'});$('restart-solo').onclick=()=>command({type:'restart-solo'});
$('ready').onclick=()=>command({type:'ready',ready:!me()?.ready});$('start').onclick=()=>command({type:'start'});$('rematch').onclick=()=>command({type:'rematch'});
$('leave').onclick=async()=>{if(active()&&!state.practice&&!confirm('Leave this watch? Your friends can keep protecting the farm.'))return;try{await api('leave');clearRoom();}catch(e){toast(e.message);}};
$('copy').onclick=async()=>{try{await navigator.clipboard.writeText(state.code);toast('Room code copied. Share the server’s Wi-Fi address too.');}catch{toast(`Room code: ${state.code}`);}};
$('help').onclick=()=>showDialog('help-dialog');document.querySelectorAll('.dialog-close,.dialog-close-button').forEach(b=>b.onclick=()=>b.closest('dialog').close());$('vote-minimize').onclick=()=>$('vote-dialog').close();$('mission-title').onclick=()=>{if(state?.phase==='vote')showDialog('vote-dialog');};$('result-close').onclick=()=>$('result-dialog').close();
const reopenVote=el('button','Choose harvest charm ✦','button secondary hidden');reopenVote.id='reopen-vote';reopenVote.onclick=()=>showDialog('vote-dialog');$('mission').append(reopenVote);

$('sound').onclick=async()=>{try{audio ||= new (window.AudioContext||window.webkitAudioContext)();await audio.resume();sound=!sound;$('sound').setAttribute('aria-pressed',String(sound));$('sound').querySelector('span').textContent=sound?'Sound on':'Sound off';if(sound)chime(523);}catch{toast('Sound is unavailable in this browser.');}};
$('fullscreen').onclick=async()=>{try{if(document.fullscreenElement)await document.exitFullscreen();else await document.querySelector('.play-column').requestFullscreen();}catch{toast('Expand is unavailable here. Try rotating your device.');}};
$('farm').addEventListener('contextmenu',e=>e.preventDefault());
$('farm').addEventListener('pointerdown',e=>{
  if(e.button!==0&&e.button!==2)return;e.preventDefault();$('farm').focus({preventScroll:true});
  if(e.button===2||(e.pointerType==='touch'&&touchPan)){drag={id:e.pointerId,x:e.clientX,y:e.clientY};camera.following=false;$('farm').setPointerCapture(e.pointerId);$('farm').classList.add('dragging');return;}
  if(!active()||!connected||me()?.ghost>0||document.querySelector('dialog[open]'))return;
  const point=screenToWorld(camera,$('farm').getBoundingClientRect(),e.clientX,e.clientY),plot=state.plots.find(p=>Math.abs(p.x-point.x)<38&&Math.abs(p.y-point.y)<38);
  selected=plot?.id??null;command({type:'move',x:plot?plot.x-Math.cos(plot.facing)*35:point.x,y:plot?plot.y-Math.sin(plot.facing)*35:point.y});renderSelection();
});
$('farm').addEventListener('pointermove',e=>{if(!drag||drag.id!==e.pointerId)return;panCamera(camera,$('farm').getBoundingClientRect(),e.clientX-drag.x,e.clientY-drag.y);drag.x=e.clientX;drag.y=e.clientY;});
for(const event of ['pointerup','pointercancel','lostpointercapture'])$('farm').addEventListener(event,e=>{if(drag?.id!==e.pointerId)return;drag=null;$('farm').classList.remove('dragging');if($('farm').hasPointerCapture(e.pointerId))$('farm').releasePointerCapture(e.pointerId);});
$('upgrade').onclick=async()=>{if(await command({type:'upgrade',plot:selected}))chime(660);};
for(const id of ['repair','touch-repair']){const b=$(id);b.onpointerdown=e=>{e.preventDefault();b.setPointerCapture(e.pointerId);repairHeld=true;};b.onpointerup=b.onpointercancel=b.onlostpointercapture=()=>repairHeld=false;}
document.querySelectorAll('[data-dir]').forEach(b=>{b.onpointerdown=e=>{e.preventDefault();b.setPointerCapture(e.pointerId);touch.add(b.dataset.dir);};b.onpointerup=b.onpointercancel=b.onlostpointercapture=()=>touch.delete(b.dataset.dir);});
window.addEventListener('keydown',e=>{if(/INPUT|TEXTAREA/.test(document.activeElement?.tagName)||document.querySelector('dialog[open]'))return;const key=e.key.toLowerCase();if(key===' '){if(state){e.preventDefault();centerCamera(camera,me());}return;}if(['w','a','s','d','arrowup','arrowdown','arrowleft','arrowright','e','1','2','3','4'].includes(key)){if(!active())return;e.preventDefault();keys.add(key);if(!e.repeat&&/^[1-4]$/.test(key))$('build-grid').children[+key-1]?.click();}});
window.addEventListener('keyup',e=>keys.delete(e.key.toLowerCase()));window.addEventListener('blur',resetControls);document.addEventListener('visibilitychange',()=>{if(document.hidden)resetControls();});
setInterval(async()=>{if(!active()||!connected||inputBusy)return;const modal=!!document.querySelector('dialog[open]');const x=modal?0:Number(keys.has('d')||keys.has('arrowright')||touch.has('right'))-Number(keys.has('a')||keys.has('arrowleft')||touch.has('left'));const y=modal?0:Number(keys.has('s')||keys.has('arrowdown')||touch.has('down'))-Number(keys.has('w')||keys.has('arrowup')||touch.has('up'));inputBusy=true;try{await command({type:'input',x,y,action:!modal&&(repairHeld||keys.has('e'))},true);}finally{inputBusy=false;}},100);
let lastFrame=0;
function animate(t){
 if(t-lastFrame>30){const canvas=$('farm'),r=canvas.getBoundingClientRect();if(r.width>0&&r.height>0){
  const pixelRatio=Math.min(devicePixelRatio||1,2),w=Math.round(r.width*pixelRatio),h=Math.round(r.height*pixelRatio);if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;}
  camera.viewWidth=r.width<600?650:1000;camera.viewHeight=camera.viewWidth*r.height/r.width;
  if(camera.following)centerCamera(camera,state&&state.phase!=='lobby'?me():null);else constrainCamera(camera);
  canvas.dataset.cameraX=camera.x.toFixed(2);canvas.dataset.cameraY=camera.y.toFixed(2);canvas.dataset.viewWidth=camera.viewWidth;canvas.dataset.following=String(camera.following);
  text('camera-mode',camera.following?'Following you':'Free camera');
  renderFarm(canvas,state,myId,selected,matchMedia('(prefers-reduced-motion: reduce)').matches?0:t/1000,camera);
 }lastFrame=t;}requestAnimationFrame(animate);
}
async function init(){try{const r=await fetch('/api/config');if(!r.ok)throw new Error('Could not reach the game server.');config=await r.json();makeCards();drawMascots($('mascots'));renderUI();requestAnimationFrame(animate);if(token){try{enter(await api('resume'));}catch(e){if(e.status===401)clearRoom();toast(e.message);}}}catch(e){toast(`${e.message} Refresh to try again.`);$('create').disabled=$('practice').disabled=true;}}
init();
