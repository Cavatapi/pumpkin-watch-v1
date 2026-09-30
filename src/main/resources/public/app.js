import { renderFarm, drawMascots, drawTowerIcon } from './art.js';

const $ = id => document.getElementById(id);
const visible = (id, on) => $(id).classList.toggle('hidden', !on);
const text = (id, value) => { if ($(id).textContent !== String(value)) $(id).textContent = value; };
const el = (tag, value, cls) => { const n = document.createElement(tag); if (value != null) n.textContent = value; if (cls) n.className = cls; return n; };
const store = { get: k => { try { return sessionStorage.getItem(k); } catch { return null; } }, set: (k,v) => { try { if(v==null) sessionStorage.removeItem(k); else sessionStorage.setItem(k,v); } catch {} } };
let config, state = null, token = store.get('pumpkin-token'), myId, stream, selected = null, skin = 'pumpkin', busy = false, connected = false;
let lastPhase = '', rosterKey = '', charmKey = '', resultShown = '', toastTimer, inputBusy = false, audio, sound = false;
const keys = new Set(), touch = new Set(); let repairHeld = false;
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
function resetControls() { keys.clear();touch.clear();repairHeld=false; }
function showDialog(id) { resetControls(); if(!$(id).open) $(id).showModal(); }
function enter(result) {
  token=result.token;myId=result.id;store.set('pumpkin-token',token);lastPhase='';resultShown='';rosterKey='';charmKey='';selected=null;state=result.state;renderUI();
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
  const owned=Object.entries(state?.charms || {});$('charms').replaceChildren();
  if(!owned.length){const teaser=el('div',null,'charm-teaser');teaser.append(el('span','×'));const words=el('div');words.append(el('strong','Better by the dozen'),el('small','Stack your strengths, together.'));teaser.append(words,el('i','✧'));$('charms').append(teaser);return;}
  for(const [id,count] of owned){const c=config.charms.find(c=>c.id===id),row=el('div',null,'owned-charm');row.title=c.description;row.append(el('span',c.icon));const words=el('div');words.append(el('strong',c.name),el('small',c.tag.toLowerCase()));row.append(words,el('b',`×${count}`));$('charms').append(row);}
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
  visible('welcome',!state);visible('room-panel',!!state);visible('touch-controls',!!active());
  text('seeds',state?fmt(state.seeds):'—');text('score',fmt(state?.score||'0'));const sleep=Math.max(0,100-(state?.wake||0));text('sleep-number',`${Math.ceil(sleep)}%`);$('sleep-bar').style.width=`${sleep}%`;$('sleep-bar').style.background=sleep<30?'#e79c83':'var(--green)';renderCharms();
  if(!state){text('phase-label','THE PATCH IS QUIET');text('clock','☾ Dusk is approaching');text('scene-caption','✦ YOUR LITTLE CORNER OF THE NIGHT');text('wave-title','DUSK');for(const dot of $('wave-dots').children)dot.className='';renderSelection();return;}
  renderRoster();const p=me(),lobby=state.phase==='lobby',host=state.host===myId;const changed=lastPhase!==state.phase;
  text('code',state.code);text('player-count',`${state.players.filter(p=>p.connected).length} / 8`);text('room-invite',state.practice?'A practice patch. Friends can join before you start.':'Friends: open the server’s Wi-Fi address, then enter this code.');
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
  if(changed){resetControls();if(state.phase==='dusk'){selected=null;toast('Your watch begins. Cover every side of the farmhouse.');}if(state.phase==='wave')chime(330);if(state.phase==='lobby'){$('result-dialog').close();resultShown='';}lastPhase=state.phase;}
  if(['won','lost'].includes(state.phase)&&resultShown!==state.phase){resultShown=state.phase;showResults();}
  if($('result-dialog').open){$('rematch').disabled=!host;text('rematch',host?'One more night →':'Waiting for the host’s next night');}
  renderSelection();
}
function makeCards() {
  const descriptions={cannon:'Nine shots. A 180° outward fan.',lantern:'Quick sparks. Better with friends.',fence:'A thorny little line of defense.',flower:'Grow seeds for bigger dreams.'};
  Object.entries(config.types).forEach(([type,spec],i)=>{const button=el('button',null,'build-card');button.dataset.type=type;button.append(el('span',i+1,'key'));const canvas=el('canvas');canvas.width=116;canvas.height=100;button.append(canvas,el('strong',spec.name),el('p',descriptions[type]),el('span',`◈ ${spec.cost}`,'cost'),el('span','+','build-arrow'));button.onclick=async()=>{if(!state){toast('Create or join a patch to plant.');$('name').focus();return;}if(selected==null){toast('Tap a marked garden plot first.');return;}if(await command({type:'build',plot:selected,build:type}))chime(523);};$('build-grid').append(button);drawTowerIcon(canvas,type);});
}
$('create').onclick=()=>join();$('practice').onclick=()=>join(true);$('join-form').onsubmit=e=>{e.preventDefault();join(false,$('room-code').value.trim().toUpperCase());};
$('name').value=store.get('pumpkin-name')||'';
document.querySelectorAll('.skin').forEach(b=>b.onclick=()=>{skin=b.dataset.skin;document.querySelectorAll('.skin').forEach(q=>{q.classList.toggle('active',q===b);q.setAttribute('aria-pressed',String(q===b));});});
$('ready').onclick=()=>command({type:'ready',ready:!me()?.ready});$('start').onclick=()=>command({type:'start'});$('rematch').onclick=()=>command({type:'rematch'});
$('leave').onclick=async()=>{if(active()&&!confirm('Leave this watch? Your friends can keep protecting the farm.'))return;try{await api('leave');clearRoom();}catch(e){toast(e.message);}};
$('copy').onclick=async()=>{try{await navigator.clipboard.writeText(state.code);toast('Room code copied. Share the server’s Wi-Fi address too.');}catch{toast(`Room code: ${state.code}`);}};
$('help').onclick=()=>showDialog('help-dialog');document.querySelectorAll('.dialog-close,.dialog-close-button').forEach(b=>b.onclick=()=>b.closest('dialog').close());$('vote-minimize').onclick=()=>$('vote-dialog').close();$('mission-title').onclick=()=>{if(state?.phase==='vote')showDialog('vote-dialog');};$('result-close').onclick=()=>$('result-dialog').close();
const reopenVote=el('button','Choose harvest charm ✦','button secondary hidden');reopenVote.id='reopen-vote';reopenVote.onclick=()=>showDialog('vote-dialog');$('mission').append(reopenVote);
$('touch-controls').parentNode.insertBefore($('touch-controls'),document.querySelector('.build-heading'));
$('sound').onclick=async()=>{try{audio ||= new (window.AudioContext||window.webkitAudioContext)();await audio.resume();sound=!sound;$('sound').setAttribute('aria-pressed',String(sound));$('sound').querySelector('span').textContent=sound?'Sound on':'Sound off';if(sound)chime(523);}catch{toast('Sound is unavailable in this browser.');}};
$('fullscreen').onclick=async()=>{try{if(document.fullscreenElement)await document.exitFullscreen();else await document.querySelector('.farm-frame').requestFullscreen();}catch{toast('Expand is unavailable here. Try rotating your device.');}};
$('farm').addEventListener('pointerdown',e=>{if(!active()||!connected||me()?.ghost>0)return;e.preventDefault();$('farm').focus({preventScroll:true});const r=$('farm').getBoundingClientRect(),x=(e.clientX-r.left)*960/r.width,y=(e.clientY-r.top)*600/r.height;const plot=state.plots.find(p=>Math.abs(p.x-x)<38&&Math.abs(p.y-y)<38);selected=plot?.id??null;command({type:'move',x:plot?plot.x-Math.cos(plot.facing)*35:x,y:plot?plot.y-Math.sin(plot.facing)*35:y});renderSelection();});
$('upgrade').onclick=async()=>{if(await command({type:'upgrade',plot:selected}))chime(660);};
for(const id of ['repair','touch-repair']){const b=$(id);b.onpointerdown=e=>{e.preventDefault();b.setPointerCapture(e.pointerId);repairHeld=true;};b.onpointerup=b.onpointercancel=b.onlostpointercapture=()=>repairHeld=false;}
document.querySelectorAll('[data-dir]').forEach(b=>{b.onpointerdown=e=>{e.preventDefault();b.setPointerCapture(e.pointerId);touch.add(b.dataset.dir);};b.onpointerup=b.onpointercancel=b.onlostpointercapture=()=>touch.delete(b.dataset.dir);});
window.addEventListener('keydown',e=>{if(/INPUT|TEXTAREA/.test(document.activeElement?.tagName)||document.querySelector('dialog[open]'))return;const key=e.key.toLowerCase();if(['w','a','s','d','arrowup','arrowdown','arrowleft','arrowright','e','1','2','3','4'].includes(key)){if(!active())return;e.preventDefault();keys.add(key);if(!e.repeat&&/^[1-4]$/.test(key))$('build-grid').children[+key-1]?.click();}});
window.addEventListener('keyup',e=>keys.delete(e.key.toLowerCase()));window.addEventListener('blur',resetControls);document.addEventListener('visibilitychange',()=>{if(document.hidden)resetControls();});
setInterval(async()=>{if(!active()||!connected||inputBusy)return;const modal=!!document.querySelector('dialog[open]');const x=modal?0:Number(keys.has('d')||keys.has('arrowright')||touch.has('right'))-Number(keys.has('a')||keys.has('arrowleft')||touch.has('left'));const y=modal?0:Number(keys.has('s')||keys.has('arrowdown')||touch.has('down'))-Number(keys.has('w')||keys.has('arrowup')||touch.has('up'));inputBusy=true;try{await command({type:'input',x,y,action:!modal&&(repairHeld||keys.has('e'))},true);}finally{inputBusy=false;}},100);
let lastFrame=0;function animate(t){if(t-lastFrame>30){renderFarm($('farm'),state,myId,selected,matchMedia('(prefers-reduced-motion: reduce)').matches?0:t/1000);lastFrame=t;}requestAnimationFrame(animate);}
async function init(){try{const r=await fetch('/api/config');if(!r.ok)throw new Error('Could not reach the game server.');config=await r.json();makeCards();drawMascots($('mascots'));renderUI();requestAnimationFrame(animate);if(token){try{enter(await api('resume'));}catch(e){if(e.status===401)clearRoom();toast(e.message);}}}catch(e){toast(`${e.message} Refresh to try again.`);$('create').disabled=$('practice').disabled=true;}}
init();
