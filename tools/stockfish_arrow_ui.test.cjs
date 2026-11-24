const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");

const source = fs.readFileSync(
  path.resolve(__dirname,"../app/src/main/assets/maia_stockfish.js"),"utf8"
);

function fakeElement(name="div") {
  const classes=new Set(),attrs={};
  const el={
    name,children:[],style:{},textContent:"",innerHTML:"",onclick:null, value:"",
    classList:{
      add(...xs){xs.forEach(x=>classes.add(x));},
      remove(...xs){xs.forEach(x=>classes.delete(x));},
      contains(x){return classes.has(x);},
      toggle(x,force){
        const next=force===undefined?!classes.has(x):!!force;
        if(next)classes.add(x);else classes.delete(x);
        return next;
      }
    },
    appendChild(child){this.children.push(child);return child;},
    insertBefore(child,before){
      if(!before)this.children.push(child);
      else this.children.splice(this.children.indexOf(before),0,child);
      return child;
    },
    get firstChild(){return this.children[0]||null;},
    setAttribute(k,v){attrs[k]=v;},
    getAttribute(k){return attrs[k];},
  };
  return el;
}
function harness({move="g1f3",maia="e2e4"}={}){
  const elements=new Map();
  const get=id=>{
    if(!elements.has(id))elements.set(id,fakeElement(id));
    return elements.get(id);
  };
  let resolveRemote;
  const state={
    turn:"white",game_over:false,predictor_ready:true,stockfish_ready:true,
    analysis_generation:10,legal_moves:["e2e4","g1f3"],
    pieces:{e2:"P",g1:"N"},settings:{model:"maia3-5m"},
  };
  const ctx={
    window:{},document:{
      getElementById:get,
      querySelector:()=>null,
      createElementNS:(ns,tag)=>fakeElement(tag),
    },
    data:state,analysisOn:true,preds:maia?[{uci:maia}]:[],
    renderHeader(){},openSettings(){},closeSettings(){},
    savePrefs(){},toast(){},prefs:{},PRESETS:{},busy:false,analysisEpoch:0,
    render(){},drawArrows(){
      const svg=get("arrows");
      svg.children=[];
      if(ctx.preds.length)svg.appendChild(fakeElement("MaiaBase"));
    },
    coords(square) {
      const x="abcdefgh".indexOf(square[0])+0.5, y=8.5-Number(square[1]);
      return [x*100,y*100];
    },
    api(apiPath){
      assert.equal(apiPath,"/api/stockfish");
      return new Promise(resolve=>{resolveRemote=resolve;});
    },
    $(){return fakeElement("$");},
  };
  vm.createContext(ctx);
  vm.runInContext(source,ctx);
  function reply(bestmove=move){
    assert.ok(resolveRemote,"Stockfish request should exist");
    resolveRemote({
      bestmove,pv:bestmove+" e7e5",depth:17,
      cp_white:35,mate_white:null
    });
  }
  return {ctx,get,state,reply};
}
async function ticks(){
  await new Promise(resolve=>setImmediate(resolve));
}
function groups(h){
  return h.get("arrows").children.filter(c=>c.name==="g");
}

test("different best moves show separate thin orange Stockfish arrow and Maia arrow",async()=>{
  const h=harness({move:"g1f3",maia:"e2e4"});
  h.ctx.render();h.reply();await ticks();
  const nodes=h.get("arrows").children;
  assert.equal(nodes[0].name,"MaiaBase");
  assert.equal(groups(h).length,1);
  assert.equal(groups(h)[0].getAttribute("class"),"sfBestArrow");
  assert.equal(groups(h)[0].children[0].getAttribute("stroke"),"#ff8b16");
  assert.equal(groups(h)[0].children[0].getAttribute("stroke-width"),"6");
  assert.match(h.get("sfMoveCompare").textContent,/Stockfish g1f3 .* Maia e2e4/);
});

test("agreement draws orange outline BELOW the thick Maia arrow",async()=>{
  const h=harness({move:"e2e4",maia:"e2e4"});
  h.ctx.render();h.reply();await ticks();
  const nodes=h.get("arrows").children;
  assert.equal(nodes.length,2);
  assert.equal(nodes[0].getAttribute("class"),"sfBestArrow agrees");
  assert.equal(nodes[1].name,"MaiaBase");
  assert.equal(nodes[0].children[0].getAttribute("stroke-width"),"19");
  assert.equal(h.get("sfMoveCompare").classList.contains("match"),true);
  assert.match(h.get("sfMoveCompare").textContent,/Same move/);
});

test("stale Stockfish result after an imported position change cannot draw",async()=>{
  const h=harness({move:"e2e4"});
  h.ctx.render();
  h.state.analysis_generation=11;
  h.ctx.render();
  h.reply("g1f3");await ticks();
  assert.equal(groups(h).length,1);
  assert.equal(groups(h)[0].getAttribute("aria-label"),"Stockfish best move: g1f3");
  // Additional redraw from a stale old request is not allowed to overwrite.
  h.state.analysis_generation=12;
  h.ctx.drawArrows();
  assert.equal(groups(h).length,0);
});

test("illegal, wrong-side and missing Stockfish moves do not draw",async()=>{
  const h=harness({maia:"e2e4"});
  h.ctx.render();h.reply("a7a6");await ticks();
  assert.equal(groups(h).length,0);
  assert.match(h.get("sfMoveCompare").textContent,/Orange: Stockfish/);
});

test("flip and analysis off always clear stale orange moves",async()=>{
  const h=harness({move:"e2e4",maia:"e2e4"});
  h.ctx.render();h.reply();await ticks();
  assert.equal(groups(h).length,1);
  h.ctx.analysisOn=false;
  h.ctx.render();
  assert.equal(groups(h).length,0);
  assert.match(h.get("sfMoveCompare").textContent,/off/);
});
