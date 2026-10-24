// Runs inside Strumline's browser on the page you're viewing. Finds the chord sheet and returns it as JSON:
// {url, title, artist, text, score}. score = how many chord lines were found (0 = nothing usable).
(function () {
  var RE = /^\(?([A-G])([#b]?)((?:maj|min|mM|m|M|dim|aug|sus|add|\+|°|ø|o)?\d{0,2}(?:(?:sus|add|maj|M|b|#|\+|-)\d{1,2})*)(?:\/([A-G])([#b]?))?\)?$/;
  var JUNK = /^(\|+|-+|x\d+|\(x\d+\)|\d+x|\/+|\.+|\(|\)|%|:|\*+|\|:|:\|)$/i;
  function clean(s) { return (s || "").replace(/ /g, " ").replace(/\r/g, ""); }
  function isChordLine(l) {
    var t = l.trim().split(/\s+/).filter(function (x) { return x && !JUNK.test(x); });
    return t.length > 0 && t.every(function (x) { return RE.test(x) || /^N\.?C\.?$/i.test(x); });
  }
  function hasInline(l) {
    var m = l.match(/\[([^\]]+)\]/g);
    return !!m && m.some(function (x) { return RE.test(x.slice(1, -1)); });
  }
  function score(text) {
    var n = 0, lines = text.split("\n");
    for (var i = 0; i < lines.length; i++) if (isChordLine(lines[i]) || hasInline(lines[i])) n++;
    return n;
  }
  function cleanTitle(t) {
    var m = t.match(/^(.*?)\s+(?:ukulele\s+)?(?:chords|tabs?|ukulele)\s*(?:\(ver\s*\d+\))?\s+by\s+(.*?)\s*(?:@|\||-\s*ultimate|$)/i);
    if (m) return { title: m[1].trim(), artist: m[2].trim() };
    m = t.match(/^(.*?)\s+(?:-|–|by)\s+(.*?)\s+(?:ukulele\s+)?(?:chords|tabs?)\b/i);
    if (m) return { title: m[2].trim(), artist: m[1].trim() };
    return { title: t.replace(/\s*(?:ukulele\s+)?(?:chords|tabs?)\b.*$/i, "").replace(/\s*[@|].*$/, "").trim(), artist: "" };
  }
  function deepFind(o, test, d) {
    d = d || 0;
    if (!o || typeof o !== "object" || d > 14) return null;
    for (var k in o) {
      var v = o[k];
      if (test(k, v)) return v;
      if (v && typeof v === "object") { var r = deepFind(v, test, d + 1); if (r != null) return r; }
    }
    return null;
  }
  var out = { url: location.href, score: 0 };
  var og = (document.querySelector('meta[property="og:title"]') || {}).content || document.title || "";

  // 1. Ultimate Guitar keeps the sheet in the data its page has already loaded.
  try {
    var store = window.UGAPP && window.UGAPP.store;
    if (store) {
      var content = deepFind(store, function (k, v) { return k === "content" && typeof v === "string" && /\[ch\]|\[tab\]/.test(v); });
      if (content) {
        out.text = clean(content);
        out.title = deepFind(store, function (k, v) { return k === "song_name" && typeof v === "string"; }) || cleanTitle(og).title;
        out.artist = deepFind(store, function (k, v) { return k === "artist_name" && typeof v === "string"; }) || cleanTitle(og).artist;
        out.score = 99;
        return JSON.stringify(out);
      }
    }
  } catch (e) {}

  // 2. Any other site: the block of text with the most chord lines (smallest block wins a tie).
  var best = null, bs = 0;
  var cands = document.querySelectorAll('pre, code, [class*="chord" i], [class*="lyric" i], [class*="tab" i], [class*="song" i], [id*="chord" i], [id*="song" i], [id*="lyric" i], article, main');
  for (var i = 0; i < cands.length; i++) {
    var txt = clean(cands[i].innerText);
    if (txt.length < 30 || txt.length > 80000) continue;
    var s = score(txt);
    if (s > bs || (s === bs && best && txt.length < best.length)) { bs = s; best = txt; }
  }
  if (best && bs >= 2) {
    var ct = cleanTitle(og);
    out.text = best; out.title = ct.title; out.artist = ct.artist; out.score = bs;
  }
  return JSON.stringify(out);
})();
