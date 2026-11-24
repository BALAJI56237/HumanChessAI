(function(){
  if(window.__hcaMaiaStockfishFeature)return;
  window.__hcaMaiaStockfishFeature=true;

  function prettyModel(name){
    const n=String(name||'maia3-5m').toLowerCase();
    if(n==='maia3-23m')return 'Maia3-23M';
    if(n==='maia3-79m')return 'Maia3-79M';
    return 'Maia3-5M';
  }

  function installModelSetting(){
    if(document.getElementById('maiaModel'))return;
    const white=document.getElementById('whiteElo');
    const row=white&&white.closest('.settingRow');
    if(!row||!row.parentNode)return;
    const modelRow=document.createElement('div');
    modelRow.className='settingRow';
    modelRow.innerHTML=`
      <div>
        <div class="name">Maia3 model</div>
        <div class="summary">Choose model size. Elo is separate; select 79M and set both ratings to 2600 for the “Maia3-79M / 2600” setup.</div>
      </div>
      <select id="maiaModel">
        <option value="maia3-5m">5M · light</option>
        <option value="maia3-23m">23M · higher accuracy</option>
        <option value="maia3-79m">79M · maximum accuracy</option>
      </select>`;
    row.parentNode.insertBefore(modelRow,row);
  }

  function installEvalPanel(){
    if(document.getElementById('sfEvalPanel'))return;
    const analysis=document.querySelector('.analysis');
    const pvs=document.getElementById('pvs');
    if(!analysis||!pvs)return;
    const panel=document.createElement('div');
    panel.id='sfEvalPanel';
    panel.className='sfEvalPanel loading';
    panel.innerHTML=`
      <div class="sfEvalTop"><span class="sfEvalName">Stockfish 19</span><span id="sfEvalScore" class="sfEvalScore">loading</span></div>
      <div class="sfEvalBar" aria-label="Stockfish evaluation">
        <div id="sfEvalWhite" class="sfEvalWhite"></div><div class="sfEvalCenter"></div>
      </div>
      <div id="sfEvalPv" class="sfEvalPv">Evaluation from White's perspective</div>
      <div id="sfMoveCompare" class="sfMoveCompare">🟠 Stockfish move · thick arrows: Maia</div>`;
    analysis.insertBefore(panel,pvs);
  }

  function updateAbout(){
    const help=document.querySelector('#aboutShade .helpText');
    if(!help)return;
    const ps=[...help.querySelectorAll('p')];
    const human=ps.find(p=>p.textContent.includes('Human move prediction:'));
    if(human)human.innerHTML='<b>Human move prediction:</b> Maia3-5M, 23M, or 79M runs on-device with ONNX Runtime.';
    const noSf=ps.find(p=>p.textContent.includes('No Stockfish:'));
    if(noSf)noSf.innerHTML='<b>Stockfish evaluation:</b> Stockfish 19 evaluates the current position only. Maia still chooses the human-like arrows, moves, and continuation lines.';
  }

  installModelSetting();
  installEvalPanel();
  updateAbout();

  const oldRenderHeader=renderHeader;
  renderHeader=function(){
    oldRenderHeader();
    if(!data)return;
    const sub=document.getElementById('sub');
    if(sub)sub.textContent=`${prettyModel(data.model_label)} · ${data.predictor_ready?'ready':'loading'}`;
  };

  const oldOpenSettings=openSettings;
  openSettings=function(){
    oldOpenSettings();
    const model=document.getElementById('maiaModel');
    if(model)model.value=(data?.settings?.model||data?.model_label||'maia3-5m');
  };

  applySettings=async function(){
    prefs.quickMove=$('#quickMove').checked;prefs.squareSelection=$('#squareSelection').value;prefs.dragPieces=$('#dragPieces').checked;prefs.scrollSpeed=+$('#scrollSpeed').value;prefs.invertScroll=$('#invertScroll').checked;
    prefs.swipeNav=$('#swipeNav').checked;prefs.autoScrollMoves=$('#autoScrollMoves').checked;prefs.autoMoveDelay=+$('#autoMoveDelay').value;prefs.showCoords=$('#showCoords').checked;prefs.discardVariations=$('#discardVariations').checked;prefs.startupGuide=$('#startupGuide').checked;savePrefs();
    let profile=$('#analysisProfile').value;
    if(PRESETS[profile]){$('#topN').value=PRESETS[profile].top_n;$('#pvCount').value=PRESETS[profile].pv_count;$('#pvPlies').value=PRESETS[profile].pv_plies}
    busy=true;analysisEpoch++;
    try{
      data=await api('/api/settings',{body:JSON.stringify({
        model:$('#maiaModel').value,
        white_elo:+$('#whiteElo').value,
        black_elo:+$('#blackElo').value,
        top_n:+$('#topN').value,
        pv_count:+$('#pvCount').value,
        pv_plies:+$('#pvPlies').value,
        analysis_profile:profile
      })});
      closeSettings();render();toast(data.predictor_ready?'Settings saved':'Loading '+prettyModel(data.model_label)+'…');
    }catch(e){toast(e.message)}
    finally{busy=false}
  };
  const applyButton=document.getElementById('applySettings');
  if(applyButton)applyButton.onclick=applySettings;

  let sfEpoch=0;
  let sfBestMove='';
  let sfBestGeneration=-1;
  const sfOrange='#ff8b16';
  const svgNS='http://www.w3.org/2000/svg';

  // Stockfish's UCI bestmove is returned for the board currently being analyzed.
  // Do not draw it if the turn or legal move list has changed.
  function validMove(uci) {
    const m=String(uci||'').trim().toLowerCase();
    if(!/^[a-h][1-8][a-h][1-8][qrbn]?$/.test(m))return '';
    if(!data||!Array.isArray(data.legal_moves)||!data.legal_moves.includes(m))return '';
    const piece=data.pieces?.[m.slice(0,2)];
    if(!piece)return '';
    const movingWhite=piece===piece.toUpperCase();
    if(movingWhite!==(data.turn==='white'))return '';
    return m;
  }
  function topMaiaMove(){
    return Array.isArray(preds)?(preds.map(p=>validMove(p?.uci)).find(Boolean)||''):'';
  }
  function updateMoveComparison(){
    const label=document.getElementById('sfMoveCompare');
    if(!label)return;
    const m=sfBestGeneration===data?.analysis_generation?validMove(sfBestMove):'';
    const human=topMaiaMove();
    const match=!!m&&!!human&&m===human;
    label.classList.toggle('match',match);
    if(!analysisOn){label.textContent='Move arrows off';return;}
    if(!m){label.textContent='Orange: Stockfish best move · thick: Maia';return;}
    if(!human){label.textContent='🟠 Stockfish '+m+' · Maia pending';return;}
    label.textContent=match?'✓ Same move · Maia + Stockfish: '+m:'🟠 Stockfish '+m+' · Maia '+human;
  }

  // Keep Maia's original arrows and draw an independent Stockfish overlay.
  // When they agree, place a wider orange silhouette UNDER Maia's thick
  // arrow: the colored border remains visible and both arrows read as one.
  const originalDrawArrows=drawArrows;
  drawArrows=function(){
    originalDrawArrows();
    const svg=document.getElementById('arrows');
    if(svg&&analysisOn&&data&&!data.game_over&&sfBestGeneration===data.analysis_generation){
      const best=validMove(sfBestMove);
      if(best){
        const same=best===topMaiaMove();
        const [x1,y1]=coords(best.slice(0,2));
        const [x2,y2]=coords(best.slice(2,4));
        const dx=x2-x1,dy=y2-y1,len=Math.hypot(dx,dy);
        if(len>0){
          const ux=dx/len,uy=dy/len,px=-uy,py=ux;
          const endX=x2-ux*20,endY=y2-uy*20;
          const group=document.createElementNS(svgNS,'g');
          group.setAttribute('class',same?'sfBestArrow agrees':'sfBestArrow');
          group.setAttribute('aria-label',same?'Stockfish agrees with Maia: '+best:'Stockfish best move: '+best);
          const body=document.createElementNS(svgNS,'line');
          for(const [k,v] of Object.entries({x1,y1,x2:endX,y2:endY})){
            body.setAttribute(k,String(v));
          }
          body.setAttribute('stroke',sfOrange);
          body.setAttribute('stroke-width',String(same?19:6));
          body.setAttribute('stroke-linecap','round');
          body.setAttribute('stroke-opacity',same?'0.98':'0.95');
          group.appendChild(body);
          const head=document.createElementNS(svgNS,'polygon');
          const base= same?19:12;
          const spread= same?22:12;
          const tip=same?3:0;
          const pts=[
            [x2+ux*tip,y2+uy*tip],
            [endX-ux*base+px*spread,endY-uy*base+py*spread],
            [endX-ux*base-px*spread,endY-uy*base-py*spread],
          ];
          head.setAttribute('points',pts.map(p=>p.join(',')).join(' '));
          head.setAttribute('fill',sfOrange);
          head.setAttribute('fill-opacity',same?'0.98':'0.95');
          group.appendChild(head);
          if(same)svg.insertBefore(group,svg.firstChild);
          else svg.appendChild(group);
        }
      }
    }
    updateMoveComparison();
  };

  function setEvalNeutral(message,off){
    const panel=document.getElementById('sfEvalPanel');
    const score=document.getElementById('sfEvalScore');
    const white=document.getElementById('sfEvalWhite');
    const pv=document.getElementById('sfEvalPv');
    if(!panel||!score||!white||!pv)return;
    panel.classList.toggle('off',!!off);
    panel.classList.add('loading');
    white.style.width='50%';
    score.textContent=message||'—';
    pv.textContent='Evaluation from White\'s perspective';
    sfBestMove='';
    sfBestGeneration=-1;
    drawArrows();
  }

  function renderEval(e){
    const panel=document.getElementById('sfEvalPanel');
    const score=document.getElementById('sfEvalScore');
    const white=document.getElementById('sfEvalWhite');
    const pv=document.getElementById('sfEvalPv');
    if(!panel||!score||!white||!pv)return;
    panel.classList.remove('loading','off');

    let pct=50,label='0.00';
    if(e.mate_white!==null&&e.mate_white!==undefined){
      const mate=Number(e.mate_white);
      pct=mate>0?98:mate<0?2:50;
      label=(mate>0?'+':'')+'M'+mate;
    }else if(e.cp_white!==null&&e.cp_white!==undefined){
      const cp=Number(e.cp_white)||0;
      pct=50+50*Math.tanh(cp/400);
      pct=Math.max(2,Math.min(98,pct));
      const pawns=cp/100;
      label=(pawns>0?'+':'')+pawns.toFixed(2);
    }
    white.style.width=pct.toFixed(1)+'%';
    score.textContent=`${label} · d${e.depth||0}`;
    const pvText=String(e.pv||'').trim();
    pv.textContent=pvText?`[SF] ${pvText}`:'Stockfish evaluation ready';
    sfBestMove=validMove(e.bestmove);
    sfBestGeneration=data.analysis_generation;
    drawArrows();
  }

  async function startStockfishEval(){
    const panel=document.getElementById('sfEvalPanel');
    if(!panel||!data)return;
    const token=++sfEpoch;
    if(!analysisOn){setEvalNeutral('off',true);return}
    if(data.game_over){setEvalNeutral(data.result||'game over',false);return}
    if(data.stockfish_error){setEvalNeutral('Stockfish error',false);document.getElementById('sfEvalPv').textContent=data.stockfish_error;return}
    if(!data.stockfish_ready){setEvalNeutral('loading',false);return}

    const g=data.analysis_generation;
    setEvalNeutral('analyzing',false);
    try{
      const e=await api('/api/stockfish',{body:JSON.stringify({generation:g,movetime_ms:300})});
      if(token!==sfEpoch||!data||g!==data.analysis_generation)return;
      renderEval(e);
    }catch(err){
      if(token!==sfEpoch||err.status===409)return;
      setEvalNeutral('unavailable',false);
      const pv=document.getElementById('sfEvalPv');if(pv)pv.textContent=err.message||'Stockfish evaluation failed';
    }
  }

  const oldRender=render;
  render=function(){
    oldRender();
    startStockfishEval();
  };

  const oldNativeReady=window.onNativeModelReady;
  window.onNativeModelReady=function(){
    if(oldNativeReady)return oldNativeReady();
    return refresh().catch(()=>{});
  };
})();
