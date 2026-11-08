/*
 * HumanChessAI FEN sharing for Chessvision.ai (and other Android text share apps).
 * No Chessvision.ai private API, image processing, account access, or network
 * calls. All submitted text is treated as untrusted FEN and validated by the
 * existing native /api/load-fen endpoint.
 */
function hcaExtractSharedFen(raw) {
  const input=String(raw||"").replace(/\u00a0/g," ").trim().slice(0,10000);
  const placementPattern=/(?:[prnbqkPRNBQK1-8]+\/){7}[prnbqkPRNBQK1-8]+/g;
  let m;
  while((m=placementPattern.exec(input))){
    const placement=m[0], rows=placement.split("/");
    if(rows.length!==8||!rows.every(r=>{
      let width=0;
      for(const p of r)width+=/^[1-8]$/.test(p)?Number(p):1;
      return width===8;
    }))continue;
    const expanded=placement.replace(/[1-8]/g,ch=>".".repeat(Number(ch)));
    if((expanded.match(/K/g)||[]).length!==1||(expanded.match(/k/g)||[]).length!==1)continue;
    const tail=input.slice(m.index+placement.length);
    const suffix=tail.match(/^\s+([wb])\s+(-|K?Q?k?q?)\s+(-|[a-h][36])(?:\s+(\d+)\s+(\d+))?/);
    const turn=suffix?suffix[1]:"w";
    const castling=suffix?suffix[2]:"-";
    const ep=suffix?suffix[3]:"-";
    const halfmove=suffix&&suffix[4]?suffix[4]:"0";
    const fullmove=suffix&&suffix[5]?suffix[5]:"1";
    return {fen:`${placement} ${turn} ${castling} ${ep} ${halfmove} ${fullmove}`,
      turn, inferredTurn:!suffix, source:input};
  }
  return null;
}
if(typeof module!=="undefined"&&module.exports){
  module.exports={hcaExtractSharedFen};
}
if(typeof window!=="undefined"&&typeof document!=="undefined"){
  (function(){
    const $fen=id=>document.getElementById(id);
    function showSharedFen(raw,fromClipboard=false){
      if($fen("settingsPage")?.classList.contains("open"))$fen("settingsPage").classList.remove("open");
      $fen("fileShade")?.classList.remove("open");
      $fen("navShade")?.classList.remove("open");
      const result=hcaExtractSharedFen(raw);
      $fen("externalFenText").value=result?.fen||"";
      $fen("externalTurn").value=result?.turn||"w";
      $fen("externalFenStatus").textContent=result
        ? (result.inferredTurn
           ? "The shared text did not specify whose turn it is. Choose White or Black before importing."
           : "FEN detected. Verify the pieces and whose turn it is before importing.")
        : (fromClipboard?"Clipboard contains no valid FEN. In Chessvision.ai choose Share → FEN Text.":"No valid FEN was found in the shared text. Use Share → FEN Text in Chessvision.ai.");
      $fen("externalFenStatus").style.color=result?"#ccebd9":"#ffc78d";
      $fen("externalFenShade").classList.add("open");
    }
    window.onExternalChessPosition=raw=>showSharedFen(raw);

    $fen("openChessvisionImport").onclick=()=>{
      let raw="";
      try{raw=AndroidBridge.clipboardText()||"";}catch(_){}
      showSharedFen(raw,true);
    };
    $fen("externalTurn").onchange=()=>{
      const parts=$fen("externalFenText").value.trim().split(/\s+/);
      if(parts.length>=2){
        parts[1]=$fen("externalTurn").value;
        $fen("externalFenText").value=parts.join(" ");
      }
    };
    $fen("externalFenText").oninput=()=>{
      const parsed=hcaExtractSharedFen($fen("externalFenText").value);
      if(parsed)$fen("externalTurn").value=parsed.turn;
    };
    $fen("externalCancel").onclick=()=>$fen("externalFenShade").classList.remove("open");
    $fen("externalFenShade").addEventListener("click",e=>{
      if(e.target.id==="externalFenShade")$fen("externalFenShade").classList.remove("open");
    });
    $fen("externalLoadFen").onclick=async()=>{
      if(busy)return;
      const extracted=hcaExtractSharedFen($fen("externalFenText").value);
      if(!extracted){
        $fen("externalFenStatus").textContent="Invalid FEN. Check that all 8 ranks and both kings are present.";
        $fen("externalFenStatus").style.color="#ffc78d";
        return;
      }
      const fields=extracted.fen.split(" ");
      fields[1]=$fen("externalTurn").value;
      const fen=fields.join(" ");
      busy=true;analysisEpoch++;
      $fen("externalLoadFen").disabled=true;
      try{
        data=await api("/api/load-fen",{body:JSON.stringify({fen})});
        // Ensure the imported position is freely reviewable from either side,
        // not constrained by the last selected player-vs-engine game mode.
        data=await api("/api/mode",{body:JSON.stringify({mode:"analysis"})});
        selected=null;quickDest=null;preds=[];
        $fen("externalFenShade").classList.remove("open");
        render();
        toast("Position imported for analysis");
      }catch(e){
        $fen("externalFenStatus").textContent=e.message||"Could not load position";
        $fen("externalFenStatus").style.color="#ffc78d";
      }finally{
        busy=false;
        $fen("externalLoadFen").disabled=false;
      }
    };
    const oldBack=window.nativeBackPressed;
    window.nativeBackPressed=()=>{
      if($fen("externalFenShade").classList.contains("open")){
        $fen("externalFenShade").classList.remove("open");
        return true;
      }
      return oldBack?oldBack():false;
    };
  })();
}
