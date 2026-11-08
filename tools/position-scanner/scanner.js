import { createRecognizer, resolveOrientation, flipPlacement, inferCastling } from "@scoriiu/fenshot";

(() => {
  if (window.__hcaImageScannerInstalled) return;
  window.__hcaImageScannerInstalled = true;

  const recognizer = createRecognizer({
    modelUrl: new URL("models/chess-tiles-v2.onnx", document.baseURI).href,
    wasmPaths: new URL("ort/", document.baseURI).href,
  });
  const names = { ".":"Erase", K:"White king", Q:"White queen", R:"White rook", B:"White bishop", N:"White knight", P:"White pawn", k:"Black king", q:"Black queen", r:"Black rook", b:"Black bishop", n:"Black knight", p:"Black pawn" };
  const icons = { K:"wk", Q:"wq", R:"wr", B:"wb", N:"wn", P:"wp", k:"bk", q:"bq", r:"br", b:"bb", n:"bn", p:"bp" };
  const pieces = [".","K","Q","R","B","N","P","k","q","r","b","n","p"];
  const files = "abcdefgh";
  let squares = Array(64).fill(".");
  let selected = ".";
  let scanning = false;
  let imageUrl = null;

  const shade = document.createElement("div");
  shade.id = "hcaImageScanner";
  shade.className = "hcaScanShade";
  shade.innerHTML = `
    <section class="hcaScanPage" role="dialog" aria-modal="true" aria-label="Scan chessboard position">
      <header class="hcaScanHeader"><strong>Scan chessboard</strong><button type="button" id="hcaScanClose" aria-label="Close scanner">✕</button></header>
      <div class="hcaScanBody">
        <p class="hcaScanHint">Import a 2D chessboard screenshot or take a clear straight-on photo of a flat diagram. Image recognition works offline. Confirm every piece before loading.</p>
        <div class="hcaScanActions">
          <button type="button" id="hcaScanGallery">Upload image</button>
          <button type="button" id="hcaScanCamera">Take photo</button>
          <input id="hcaScanGalleryInput" type="file" accept="image/*" hidden>
          <input id="hcaScanCameraInput" type="file" accept="image/*" capture="environment" hidden>
        </div>
        <img id="hcaScanOriginal" class="hcaScanOriginal" alt="Imported image" hidden>
        <p id="hcaScanStatus" class="hcaScanStatus" aria-live="polite">Choose an image to start.</p>
        <div class="hcaScanBoardHeader"><strong>Review and edit</strong><button type="button" id="hcaScanRotate">Rotate read 180°</button></div>
        <div id="hcaScanBoard" class="hcaScanBoard" aria-label="Editable board, White at bottom"></div>
        <p class="hcaScanHint">Select a piece below, then tap a square to correct it. Board always displays White at the bottom.</p>
        <div id="hcaScanPalette" class="hcaScanPalette" role="group" aria-label="Piece selection"></div>
        <fieldset class="hcaScanOptions"><legend>Who moves next?</legend>
          <label><input type="radio" name="hcaScanTurn" value="w" checked> White</label>
          <label><input type="radio" name="hcaScanTurn" value="b"> Black</label>
        </fieldset>
        <fieldset class="hcaScanOptions"><legend>Castling rights (cannot be determined from an image)</legend>
          <label><input type="checkbox" data-castle="K"> White O-O</label>
          <label><input type="checkbox" data-castle="Q"> White O-O-O</label>
          <label><input type="checkbox" data-castle="k"> Black O-O</label>
          <label><input type="checkbox" data-castle="q"> Black O-O-O</label>
          <button type="button" id="hcaScanGuessCastle">Suggest from piece squares</button>
        </fieldset>
        <label class="hcaScanLabel">En passant target (usually none)
          <input id="hcaScanEp" type="text" placeholder="—" value="-" maxlength="2" autocapitalize="off" spellcheck="false">
        </label>
        <label class="hcaScanLabel">FEN preview
          <textarea id="hcaScanFen" readonly rows="2" spellcheck="false"></textarea>
        </label>
        <button type="button" id="hcaScanLoad" class="hcaScanLoad">Load position into HumanChessAI</button>
      </div>
    </section>`;
  document.body.appendChild(shade);
  const el = id => document.getElementById(id);
  const boardEl = el("hcaScanBoard");
  const paletteEl = el("hcaScanPalette");

  function img(piece) {
    if (piece === ".") return '<span class="hcaScanEmpty">×</span>';
    return '<img draggable="false" src="pieces/chesscases/' + icons[piece] + '.svg" alt="' + names[piece] + '">';
  }

  function parse(placement) {
    const rows = placement.split("/");
    if (rows.length !== 8) throw new Error("Invalid scanned board");
    const out = [];
    for (const row of rows) {
      for (const ch of row) {
        if (/^[1-8]$/.test(ch)) out.push(...Array(Number(ch)).fill("."));
        else if (icons[ch]) out.push(ch);
        else throw new Error("Unexpected piece in scan");
      }
    }
    if (out.length !== 64) throw new Error("Board did not contain 64 squares");
    return out;
  }

  function placement() {
    const rows = [];
    for (let r=0;r<8;r++) {
      let row = "", blanks = 0;
      for (let c=0;c<8;c++) {
        const piece = squares[r*8+c];
        if (piece === ".") blanks++;
        else { if (blanks) row += blanks; blanks=0; row+=piece; }
      }
      if (blanks) row+=blanks;
      rows.push(row);
    }
    return rows.join("/");
  }

  function currentFen() {
    const turn = shade.querySelector('input[name="hcaScanTurn"]:checked')?.value || "w";
    const castle = [...shade.querySelectorAll("[data-castle]:checked")].map(x=>x.dataset.castle).join("") || "-";
    const ep = el("hcaScanEp").value.trim().toLowerCase() || "-";
    return placement() + " " + turn + " " + castle + " " + ep + " 0 1";
  }

  function status(message, warning=false) {
    const x=el("hcaScanStatus");
    x.textContent=message;
    x.classList.toggle("warning", warning);
  }

  function renderBoard() {
    boardEl.innerHTML = squares.map((p,i)=>{
      const rank=8-Math.floor(i/8), file=files[i%8];
      const dark=((Math.floor(i/8)+i%8)%2===0);
      return '<button type="button" class="hcaScanSquare '+(dark?'dark':'light')+'" data-square="'+i+'" aria-label="'+file+rank+' '+(names[p]||'Empty')+'">'+(p==="."?"":img(p))+'</button>';
    }).join("");
    el("hcaScanFen").value=currentFen();
  }

  function selectPiece(piece) {
    selected=piece;
    [...paletteEl.querySelectorAll("button")].forEach(btn=>{
      btn.classList.toggle("selected", btn.dataset.piece===piece);
      btn.setAttribute("aria-pressed",String(btn.dataset.piece===piece));
    });
  }

  paletteEl.innerHTML=pieces.map(p=>'<button type="button" data-piece="'+p+'" title="'+names[p]+'" aria-label="'+names[p]+'">'+img(p)+'</button>').join("");
  paletteEl.addEventListener("click",e=>{
    const b=e.target.closest("button[data-piece]");
    if (b) selectPiece(b.dataset.piece);
  });
  boardEl.addEventListener("click",e=>{
    const b=e.target.closest("button[data-square]");
    if (!b) return;
    squares[Number(b.dataset.square)]=selected;
    renderBoard();
    status("Position edited. Check both kings and select who moves next.");
  });
  shade.addEventListener("change",e=>{
    if (e.target.matches('[data-castle],input[name="hcaScanTurn"],#hcaScanEp')) el("hcaScanFen").value=currentFen();
  });
  el("hcaScanEp").addEventListener("input",()=>el("hcaScanFen").value=currentFen());
  el("hcaScanRotate").onclick=()=>{
    squares=squares.slice().reverse();
    renderBoard();
    status("Read rotated 180°. Check piece positions before loading.");
  };
  el("hcaScanGuessCastle").onclick=()=>{
    const guess=inferCastling(placement());
    shade.querySelectorAll("[data-castle]").forEach(ch=>ch.checked=guess.includes(ch.dataset.castle));
    el("hcaScanFen").value=currentFen();
    status("Castling suggested from king/rook squares only; confirm historical rights.");
  };

  function checkPosition() {
    if (squares.filter(p=>p==="K").length!==1 || squares.filter(p=>p==="k").length!==1)
      throw new Error("Exactly one white king and one black king are required.");
    if (squares.filter(p=>p==="P").length>8 || squares.filter(p=>p==="p").length>8)
      throw new Error("A side cannot have more than eight pawns.");
    for (const p of [...squares.slice(0,8),...squares.slice(56)]) {
      if (p==="p"||p==="P") throw new Error("Pawns cannot be on rank 1 or rank 8.");
    }
    const ep=el("hcaScanEp").value.trim().toLowerCase()||"-";
    if (!/^(-|[a-h][36])$/.test(ep)) throw new Error("En passant must be '-' or a square such as e3/e6.");
    const rights=[...shade.querySelectorAll("[data-castle]:checked")].map(x=>x.dataset.castle);
    const inferred=inferCastling(placement());
    if (rights.some(x=>!inferred.includes(x))) throw new Error("Selected castling right has no king and rook on their starting squares.");
    return currentFen();
  }

  async function recognize(file) {
    if (!file || scanning) return;
    if (!file.type.startsWith("image/")) {status("Select a JPEG, PNG, or other image.",true);return;}
    if (file.size>25*1024*1024) {status("Image is too large (25 MB limit). Crop or resize it.",true);return;}
    scanning=true;
    el("hcaScanGallery").disabled=true;
    el("hcaScanCamera").disabled=true;
    el("hcaScanLoad").disabled=true;
    if (imageUrl) URL.revokeObjectURL(imageUrl);
    imageUrl=URL.createObjectURL(file);
    const preview=el("hcaScanOriginal");
    preview.src=imageUrl;
    preview.hidden=false;
    status("Detecting chessboard and reading 64 squares locally…");
    try {
      const scan=await recognizer.recognize(file);
      if (!scan) throw new Error("No complete 2D chessboard found. Use a clearer, straight-on screenshot.");
      const oriented=resolveOrientation(scan.placement);
      squares=parse(oriented.placement);
      renderBoard();
      const pct=(scan.meanConfidence*100).toFixed(0);
      if (!scan.plausible || !scan.reliable) {
        status("Low-confidence scan ("+pct+"%). Correct every square, especially kings, before loading.", true);
      } else {
        status("Detected "+(oriented.orientation==="black"?"Black-at-bottom":"White-at-bottom")+" image ("+pct+"% mean confidence). Review pieces and choose side to move.");
      }
    } catch(err) {
      status("Scan failed: "+(err?.message||String(err)),true);
    } finally {
      scanning=false;
      el("hcaScanGallery").disabled=false;
      el("hcaScanCamera").disabled=false;
      el("hcaScanLoad").disabled=false;
    }
  }

  function open() {
    document.querySelector("#navShade")?.classList.remove("open");
    document.querySelector("#fileShade")?.classList.remove("open");
    shade.classList.add("open");
  }
  function close(){shade.classList.remove("open");}
  el("hcaScanClose").onclick=close;
  shade.addEventListener("click",e=>{if(e.target===shade)close();});
  el("hcaScanGallery").onclick=()=>el("hcaScanGalleryInput").click();
  el("hcaScanCamera").onclick=()=>el("hcaScanCameraInput").click();
  for (const id of ["hcaScanGalleryInput","hcaScanCameraInput"]) {
    el(id).addEventListener("change",e=>{const f=e.target.files?.[0];e.target.value="";if(f)void recognize(f);});
  }

  el("hcaScanLoad").onclick=async()=>{
    try {
      const fen=checkPosition();
      el("hcaScanLoad").disabled=true;
      status("Loading verified FEN into Maia and Stockfish…");
      await loadFen(fen);
      if (typeof data!=="undefined" && data?.fen===fen) close();
      else status("Could not load position. Check the FEN and legality.",true);
    } catch(err){status(err?.message||String(err),true);}
    finally{el("hcaScanLoad").disabled=false;}
  };

  const nav=document.querySelector("#navShade .navDrawer");
  if (nav) {
    const b=document.createElement("button");
    b.className="navItem";
    b.textContent="Scan position from image";
    b.onclick=open;
    nav.insertBefore(b,nav.children[2]||null);
  }
  const file=document.querySelector("#fileShade .dialog");
  if (file) {
    const b=document.createElement("button");
    b.className="action";
    b.textContent="Scan/upload chessboard image";
    b.onclick=open;
    file.insertBefore(b,el("copyFen"));
  }
  const oldBack=window.nativeBackPressed;
  window.nativeBackPressed=()=>shade.classList.contains("open")?(close(),true):(oldBack?oldBack():false);
  selectPiece(".");
  renderBoard();
})();
