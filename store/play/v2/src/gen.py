#!/usr/bin/env python3
"""Generates the v2 Play Store HTML sources (liquid-glass UI). Run: python3 gen.py"""
from pathlib import Path

here = Path(__file__).parent
shots = here / "shots"
shots.mkdir(exist_ok=True)

CSS = """
/* Tokens copied from ui/theme/Theme.kt (MIDNIGHT, liquid-glass build). */
:root{
  --acc:#8CB8FF; --accdim:#3F67B5; --bg:#0A0E1A; --on:#F1F3F8; --onsv:#A9B1C1;
  --sv:#2B3452; --out:#7D86A6; --err:#FF8A80; --onprim:#06080D; --dlg:#1B2030;
  --blobA:#2E5BFF; --blobB:#8A3FFC;
}
*{box-sizing:border-box}
html,body{margin:0;padding:0;width:1080px;height:1920px;overflow:hidden}
body{background:var(--bg);position:relative;color:var(--on);
  font-family:-apple-system,"Helvetica Neue",Helvetica,Arial,sans-serif;-webkit-font-smoothing:antialiased}
.backdrop{position:absolute;inset:0;
  background:
   radial-gradient(900px 900px at 8% 6%, rgba(46,91,255,.42), rgba(46,91,255,0) 70%),
   radial-gradient(1000px 1000px at 96% 92%, rgba(138,63,252,.34), rgba(138,63,252,0) 70%),
   var(--bg)}
.cap{position:absolute;top:96px;left:64px;right:64px;text-align:center}
.cap h2{margin:0;font-size:64px;line-height:1.08;font-weight:700;letter-spacing:-2px;color:#F7F9FF}
.cap p{margin:18px 0 0;font-size:31px;line-height:1.3;color:#AEB7CC;letter-spacing:-.2px}
.panel{position:absolute;left:48px;top:300px;width:984px;height:1573px;border-radius:52px;overflow:hidden;
  border:1px solid rgba(255,255,255,.14);box-shadow:0 34px 90px rgba(0,0,0,.6)}
.ui{zoom:2.3942;width:411px;height:657px;position:relative;display:flex;flex-direction:column;
  background:
   radial-gradient(520px 520px at 8% 4%, rgba(46,91,255,.42), rgba(46,91,255,0) 70%),
   radial-gradient(560px 560px at 96% 96%, rgba(138,63,252,.34), rgba(138,63,252,0) 70%),
   var(--bg)}
.glass{background:rgba(255,255,255,.08);
  box-shadow:inset 0 0 0 1px rgba(255,255,255,.13), inset 0 1px 0 rgba(255,255,255,.26)}
.glass2{background:rgba(255,255,255,.14);
  box-shadow:inset 0 0 0 1px rgba(255,255,255,.16), inset 0 1px 0 rgba(255,255,255,.3)}
.sb{height:24px;flex:0 0 auto;display:flex;align-items:center;padding:0 14px;font-size:11px;font-weight:600}
.sb .sp{flex:1}.sb svg{margin-left:5px}
.hl{font-size:28px;font-weight:700;letter-spacing:-.6px}
.tl{font-size:22px;font-weight:600;letter-spacing:-.3px}
.tm{font-size:16px;font-weight:600}
.ts{font-size:14px;font-weight:600}
.bm{font-size:13px;line-height:19px}
.bs{font-size:12px;line-height:16px}
.lb{font-size:14px;font-weight:500;letter-spacing:.2px}
.ls{font-size:11px;font-weight:600;letter-spacing:.8px}
.onsv{color:var(--onsv)}.acc{color:var(--acc)}
/* floating tab bar */
.nav{flex:0 0 auto;margin:10px 20px;padding:6px;border-radius:32px;display:flex;align-items:center;height:60px}
.tab{flex:1;height:48px;border-radius:26px;display:flex;align-items:center;justify-content:center;gap:8px;color:var(--onsv)}
.tab.sel{flex:1.7;background:rgba(140,184,255,.22);color:var(--acc);font-size:14px;font-weight:500}
.tab svg{stroke:currentColor}
/* switch */
.sw{width:52px;height:32px;border-radius:16px;position:relative;flex:0 0 auto}
.sw i{position:absolute;border-radius:50%;top:50%;transform:translateY(-50%)}
.sw.on{background:var(--acc)}.sw.on i{width:24px;height:24px;right:4px;background:var(--onprim)}
.sw.off{background:var(--sv);border:2px solid var(--out)}.sw.off i{width:16px;height:16px;left:6px;background:var(--out)}
.pill{border-radius:12px;padding:8px 14px;display:flex;align-items:center;gap:6px;white-space:nowrap;
  font-size:14px;font-weight:500;letter-spacing:.2px;color:var(--onsv);background:rgba(255,255,255,.08)}
.pill.sel{background:var(--acc);color:var(--onprim)}
.pill b{font-size:12px;font-weight:500;opacity:.6}
"""
(shots / "shared.css").write_text(CSS)

STATUS = """<div class="sb">9:41<div class="sp"></div>
<svg width="15" height="11" viewBox="0 0 15 11"><path d="M1 10h2V7H1zm3.5 0h2V4.5h-2zm3.5 0h2V2H8zm3.5 0h2V0h-2z" fill="#F1F3F8"/></svg>
<svg width="14" height="11" viewBox="0 0 14 11"><path d="M7 9.5 1 3.6A8.4 8.4 0 0 1 13 3.6z" fill="none" stroke="#F1F3F8" stroke-width="1.3"/></svg>
<svg width="20" height="11" viewBox="0 0 20 11"><rect x="0.6" y="1.4" width="16" height="8.2" rx="2.2" fill="none" stroke="#F1F3F8" stroke-width="1.2"/><rect x="2.2" y="3" width="11" height="5" rx="1.2" fill="#F1F3F8"/><path d="M18 4.2v4a2.2 2.2 0 0 0 0-4z" fill="#F1F3F8"/></svg></div>"""

def ic(path, size=24, sw=1.7, fill="none", extra=""):
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="{fill}" stroke-width="{sw}" '
            f'stroke-linecap="round" stroke-linejoin="round" {extra}>{path}</svg>')

I = {
 "lock":  '<rect x="4.5" y="10.5" width="15" height="10.5" rx="2.2"/><path d="M8 10.5V7a4 4 0 0 1 8 0v3.5"/>',
 "vault": '<rect x="7.5" y="3.5" width="13" height="13" rx="2.2"/><path d="M4.5 7v12.5a1 1 0 0 0 1 1H17"/><circle cx="11.6" cy="7.8" r="1.2"/><path d="M8.6 14.4 12 11l2.4 2.3 1.8-1.6 3.4 3.4"/>',
 "eye":   '<path d="M2.5 12S6 6.5 12 6.5 21.5 12 21.5 12 18 17.5 12 17.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.8"/>',
 "sliders":'<path d="M4 7.5h8M17.5 7.5H20M4 16.5h2.5M12 16.5h8"/><circle cx="14.8" cy="7.5" r="2.3"/><circle cx="9.2" cy="16.5" r="2.3"/>',
 "search":'<circle cx="10.8" cy="10.8" r="6.5"/><path d="M15.6 15.6 20 20"/>',
 "chat":  '<path d="M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v7a2.5 2.5 0 0 1-2.5 2.5H9l-5 4z"/>',
 "gallery":'<rect x="3" y="4.5" width="18" height="15" rx="2.6"/><circle cx="8.6" cy="9.8" r="1.7"/><path d="M3.6 17.4 9.2 12l4 3.7 3-2.6 4.2 4.3"/>',
 "wallet":'<rect x="3" y="6" width="18" height="12" rx="2.4"/><path d="M3 10.5h18"/><path d="M6.5 14.5h4"/>',
 "globe": '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.4 2.3 3.6 5.2 3.6 8.5S14.4 18.2 12 20.5c-2.4-2.3-3.6-5.2-3.6-8.5S9.6 5.8 12 3.5z"/>',
 "finger":'<path d="M6 11a6 6 0 0 1 12 0M9 20c-.6-1.5-.8-3-.8-4.6a3.8 3.8 0 0 1 7.6 0c0 1.9.2 3.4.8 4.6M12 12v3.4c0 1.8.3 3.2 1 4.6M4.5 8.5A9 9 0 0 1 12 4.5a9 9 0 0 1 7.5 4"/>',
 "back":  '<path d="M20 12H5M11 6l-6 6 6 6"/>',
 "sweep": '<path d="M5 7h14M9 7V4.5h6V7M7 7l1 12h8l1-12"/>',
 "history":'<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
 "plus":  '<path d="M12 5.5v13M5.5 12h13"/>',
 "backsp":'<path d="M9 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6-7z"/><path d="M12.5 9.5l5 5M17.5 9.5l-5 5"/>',
}
def tabs(sel):
    items = [("Apps","lock"),("Vault","vault"),("Intruders","eye"),("Settings","sliders")]
    out = '<div class="nav glass2">'
    for name, k in items:
        if name == sel:
            out += f'<div class="tab sel">{ic(I[k])}{name}</div>'
        else:
            out += f'<div class="tab">{ic(I[k])}</div>'
    return out + '</div>'

def page(title, sub, body):
    return f"""<!doctype html><meta charset="utf-8">
<link rel="stylesheet" href="shared.css">
<div class="backdrop"></div>
<div class="cap"><h2>{title}</h2><p>{sub}</p></div>
<div class="panel"><div class="ui">
{STATUS}
{body}
</div></div>"""

def app_row(name, glyph, sub, on, subcls="acc", icocol="#8CB8FF"):
    return f"""<div class="glass" style="border-radius:18px;padding:10px 12px;display:flex;align-items:center;flex:0 0 auto">
<div style="width:44px;height:44px;border-radius:14px;background:rgba(255,255,255,.1);display:flex;align-items:center;justify-content:center;margin-right:14px;color:{icocol}">{ic(I[glyph],30,1.6,extra='stroke="currentColor"')}</div>
<div style="flex:1"><div class="tm">{name}</div><div class="bs {subcls}">{sub}</div></div>
<div class="sw {'on' if on else 'off'}"><i></i></div></div>"""

def header_block(sub="Everything private stays private"):
    return f"""<div style="padding:12px 20px 8px">
<div class="hl">Protected apps</div><div class="bm onsv" style="margin-top:2px">{sub}</div>
<div class="glass" style="margin-top:12px;height:56px;border-radius:16px;display:flex;align-items:center;gap:12px;padding:0 16px;font-size:15px;color:var(--onsv)">{ic(I['search'],22,1.7,extra='stroke="#A9B1C1"')}Search apps</div>
<div style="display:flex;gap:8px;margin-top:10px;align-items:center">
<div class="pill sel">All</div><div class="pill">Locked<b>6</b></div><div class="pill">Unlocked<b>41</b></div>
<div style="width:1px;height:22px;background:rgba(255,255,255,.14)"></div><div class="pill sel">Any</div></div></div>"""

def stats(a,b,c):
    def s(v,l): return f'<div class="glass" style="flex:1;border-radius:20px;padding:12px 14px"><div class="hl">{v}</div><div class="ls onsv">{l}</div></div>'
    return f'<div style="display:flex;gap:10px">{s(a,"Locked")}{s(b,"Unlocks today")}{s(c,"Intruder shots")}</div>'

def profiles(active="Work"):
    def chip(n):
        sel = n == active
        return f'<div class="pill{" sel" if sel else ""}">{n}</div>'
    return (f'<div style="display:flex;gap:8px;align-items:center">{chip("Work")}{chip("Home")}{chip("Kids")}'
            f'<div class="pill acc" style="background:rgba(140,184,255,.14);color:var(--acc)">{ic(I["plus"],15,2,extra="stroke=\"currentColor\"")}Save as profile</div></div>')

def suggestion():
    return """<div class="glass2" style="border-radius:22px;padding:12px 10px 12px 16px;display:flex;align-items:center">
<div style="flex:1"><div class="ts">Suggested to lock</div><div class="bm onsv">Messages, Gallery, Wallet +2 more</div></div>
<div style="background:var(--acc);color:var(--onprim);border-radius:20px;padding:11px 20px;font-size:14px;font-weight:600">Lock 5</div></div>"""

# ---------------------------------------------------------------- 01 lock
def dots(n, filled):
    d = ""
    for i in range(n):
        if i < filled: d += '<div style="width:14px;height:14px;border-radius:50%;background:var(--acc)"></div>'
        else: d += '<div style="width:14px;height:14px;border-radius:50%;border:2px solid var(--out)"></div>'
    return f'<div style="display:flex;gap:18px;justify-content:center">{d}</div>'
def key(c):
    return f'<div class="glass" style="border-radius:26px;height:70px;display:flex;align-items:center;justify-content:center;font-size:26px;font-weight:300">{c}</div>'
keypad = "".join(key(c) for c in "123456789")
keypad += f'<div style="display:flex;align-items:center;justify-content:center;color:var(--acc)">{ic(I["finger"],32,1.6,extra="stroke=\"currentColor\"")}</div>{key(0)}<div style="display:flex;align-items:center;justify-content:center;color:var(--onsv)">{ic(I["backsp"],26,1.7,extra="stroke=\"currentColor\"")}</div>'
lock_body = f"""<div style="flex:1;display:flex;flex-direction:column;align-items:center;padding-top:44px">
<div class="glass" style="width:72px;height:72px;border-radius:20px;display:flex;align-items:center;justify-content:center;color:var(--acc)">{ic(I['chat'],44,1.5,extra='stroke="currentColor"')}</div>
<div class="tl" style="margin-top:18px;font-size:20px">Messages</div>
<div class="bm onsv" style="margin-top:6px">Enter your PIN</div>
<div style="margin-top:26px">{dots(4,2)}</div>
<div style="margin-top:30px;width:340px;display:grid;grid-template-columns:repeat(3,1fr);gap:10px">{keypad}</div>
<div class="lb acc" style="margin-top:20px">Close</div></div>"""
(shots/"01-lock.html").write_text(page("Nothing opens without you","PIN, pattern, fingerprint or your phone's screen lock.",lock_body))

# ---------------------------------------------------------------- 02 apps
apps_list = f"""<div style="flex:1;overflow:hidden;padding:4px 16px 0;display:flex;flex-direction:column;gap:12px">
{stats("6","14","3")}
{suggestion()}
{profiles()}
{app_row("Gallery","gallery","Locked",True)}
{app_row("Messages","chat","Locked",True)}
</div>"""
(shots/"02-apps.html").write_text(page("Lock any app in one tap","A dashboard, smart suggestions and one-tap profiles.",
    header_block()+apps_list+tabs("Apps")))

# ---------------------------------------------------------------- 03 timed unlock
def btn(t): return f'<div style="padding:10px 0;font-size:14px;font-weight:500;color:var(--acc)">{t}</div>'
timed = f"""{header_block()}
<div style="flex:1;overflow:hidden;padding:4px 16px 0;display:flex;flex-direction:column;gap:8px">
{app_row("Gallery","gallery","Locked",True)}
{app_row("Messages","chat","Unlocked for 30 more min",True)}
{app_row("Wallet","wallet","Locked",True)}
{app_row("Browser","globe","Not locked",False,"onsv","#A9B1C1")}
</div>{tabs("Apps")}
<div style="position:absolute;inset:0;background:rgba(0,0,0,.62);display:flex;align-items:center;justify-content:center">
<div style="width:300px;background:var(--dlg);border-radius:28px;padding:24px">
<div class="tl">Unlock Messages for…</div>
<div class="bm onsv" style="margin-top:12px">It opens without a PIN until the time is up, even if you leave it or turn the screen off.</div>
<div style="margin-top:8px">{btn("15 minutes")}{btn("30 minutes")}{btn("1 hour")}{btn("2 hours")}{btn("Relock now")}</div>
<div style="text-align:right;margin-top:6px;font-size:14px;font-weight:500;color:var(--acc)">Cancel</div>
</div></div>"""
(shots/"03-timed.html").write_text(page("Unlock for a while, then relock","Give an app 15 minutes to 2 hours — it relocks itself.",timed))

# ---------------------------------------------------------------- 04 settings (tamper / appearance)
def sechead(t, icon):
    return f'<div style="padding:22px 0 10px 6px;display:flex;align-items:center;gap:8px" class="ls onsv">{ic(icon,15,0,fill="#8CB8FF")}{t}</div>'
def srow(icon, t, s, on=None, err=False):
    col = "#FF8A80" if err else "#8CB8FF"
    sw = '' if on is None else f'<div class="sw {"on" if on else "off"}"><i></i></div>'
    return f"""<div style="padding:12px 14px;display:flex;align-items:center">
<div style="width:34px;height:34px;border-radius:11px;background:rgba(255,255,255,.1);display:flex;align-items:center;justify-content:center;margin-right:14px;color:{col}">{ic(icon,18,1.8,extra='stroke="currentColor"')}</div>
<div style="flex:1;margin-right:10px"><div class="tm">{t}</div><div class="bm onsv">{s}</div></div>{sw}</div>"""
div = '<div style="height:1px;margin:0 16px 0 62px;background:rgba(255,255,255,.08)"></div>'
shield = '<path d="M12 2 4 5v7c0 5.2 3.4 8.9 8 10.5 4.6-1.6 8-5.3 8-10.5V5z"/>'
settings = f"""<div style="flex:1;overflow:hidden;padding:0 16px">
<div class="hl" style="padding:12px 0 4px">Settings</div>
<div class="glass" style="border-radius:22px;padding:16px;display:flex;align-items:center;background:rgba(140,184,255,.14)">
<div style="width:44px;height:44px;border-radius:14px;background:rgba(140,184,255,.24);display:flex;align-items:center;justify-content:center;margin-right:14px">{ic(shield,24,0,fill="#8CB8FF")}</div>
<div style="flex:1"><div class="tm">Protection active</div><div class="bm onsv">6 apps locked</div></div><div class="sw on"><i></i></div></div>
{sechead("PROTECTION", shield)}
<div class="glass" style="border-radius:22px;padding:6px 0">
{srow(I['lock'],"Lock newly installed apps","Anything you install later starts protected",False)}{div}
{srow(I['chat'],"Fake “not responding” screen","Shows a crash dialog instead of the keypad. Long-press the message to unlock.",True)}{div}
{srow(I['history'],"Lock history","Recent unlocks and wrong attempts")}</div>
{sechead("TAMPER PROTECTION", shield)}
<div class="glass" style="border-radius:22px;padding:6px 0">
{srow(I['eye'],"Hide locked apps from recents","The app switcher has no picture of it to show.",True)}{div}
{srow(shield,"Uninstall protection","On — AppLock cannot be uninstalled.",True)}</div>
{sechead("APPEARANCE", shield)}
<div class="glass" style="border-radius:22px;padding:10px;display:flex;gap:8px">
<div class="pill sel">Midnight</div><div class="pill" style="background:rgba(43,52,82,.6)">Brass</div><div class="pill" style="background:rgba(43,52,82,.6)">Moss</div><div class="pill" style="background:rgba(43,52,82,.6)">Slate</div></div>
</div>"""
(shots/"04-settings.html").write_text(page("Set it up the way you live","Fake crash screen, five themes, uninstall protection.",settings+tabs("Settings")))

# ---------------------------------------------------------------- 05 intruders
def silhouette(i):
    tint = ["#3E4855","#443E55","#3A5048","#554A3E"][i % 4]
    return f"""<div style="position:absolute;inset:0;background:radial-gradient(115% 85% at 50% 20%,{tint} 0%,#242C36 48%,#121821 100%)">
<svg viewBox="0 0 100 100" style="position:absolute;left:0;bottom:0;width:100%;height:74%"><circle cx="50" cy="44" r="14" fill="#0C1118" opacity=".78"/><path d="M20 100c0-20 13-31 30-31s30 11 30 31z" fill="#0C1118" opacity=".78"/></svg></div>"""
def tile(i, t):
    return f"""<div style="position:relative;border-radius:24px;overflow:hidden;height:225px;box-shadow:inset 0 0 0 1px rgba(255,255,255,.14)">{silhouette(i)}
<div style="position:absolute;left:0;right:0;bottom:0;padding:26px 12px 10px;background:linear-gradient(to bottom,rgba(0,0,0,0),rgba(0,0,0,.82))">
<div class="lb" style="color:#fff">Messages</div><div class="ls" style="color:rgba(255,255,255,.75)">{t}</div></div></div>"""
intr = f"""<div style="padding:12px 20px 8px"><div class="hl">Intruders</div><div class="bm onsv" style="margin-top:2px">6 photos · deleted after 30 days</div>
<div class="glass" style="display:inline-flex;gap:4px;padding:5px;border-radius:16px;margin-top:12px">
<div style="width:38px;height:38px"></div></div></div>
<div style="flex:1;overflow:hidden;padding:4px 16px 0;display:grid;grid-template-columns:1fr 1fr;gap:10px;align-content:start">
{tile(0,"24 Oct, 12:00 PM")}{tile(1,"24 Oct, 11:58 AM")}{tile(2,"23 Oct, 9:12 PM")}{tile(3,"23 Oct, 9:11 PM")}</div>{tabs("Intruders")}"""
# replace the placeholder picker with real 3-way picker
picker = f"""<div class="glass" style="display:inline-flex;gap:4px;padding:5px;border-radius:16px;margin-top:12px">
<div style="width:38px;height:38px;border-radius:11px;display:flex;align-items:center;justify-content:center;color:var(--onsv)">{ic('<rect x="4" y="5" width="16" height="5" rx="1.5"/><rect x="4" y="14" width="16" height="5" rx="1.5"/>',20,1.7,extra='stroke="currentColor"')}</div>
<div style="width:38px;height:38px;border-radius:11px;background:var(--acc);display:flex;align-items:center;justify-content:center;color:var(--onprim)">{ic('<rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/>',20,1.7,extra='stroke="currentColor"')}</div>
<div style="width:38px;height:38px;border-radius:11px;display:flex;align-items:center;justify-content:center;color:var(--onsv)">{ic('<path d="M4 4h4v4H4zM10 4h4v4h-4zM16 4h4v4h-4zM4 10h4v4H4zM10 10h4v4h-4zM16 10h4v4h-4z"/>',20,1.5,extra='stroke="currentColor"')}</div></div>"""
intr = intr.replace('<div class="glass" style="display:inline-flex;gap:4px;padding:5px;border-radius:16px;margin-top:12px">\n<div style="width:38px;height:38px"></div></div>', picker)
(shots/"05-intruders.html").write_text(page("See who tried to get in","Swipe through photos taken after repeated wrong attempts.",intr))

# ---------------------------------------------------------------- 06 history
def hrow(name, when, kind):
    col = "var(--err)" if kind == "Wrong attempt" else "var(--acc)"
    return f"""<div class="glass" style="border-radius:16px;padding:14px;display:flex;align-items:center">
<div style="flex:1"><div class="tm">{name}</div><div class="bs onsv">{when}</div></div><div class="lb" style="color:{col}">{kind}</div></div>"""
hist = f"""<div style="display:flex;align-items:center;padding:6px 8px 6px 12px">
<div style="width:40px;display:flex;align-items:center;color:var(--on)">{ic(I['back'],24,1.8,extra='stroke="currentColor"')}</div>
<div class="tl" style="flex:1">Lock history</div><div style="color:var(--on);width:40px">{ic(I['sweep'],24,1.7,extra='stroke="currentColor"')}</div></div>
<div style="flex:1;overflow:hidden;padding:12px 16px;display:flex;flex-direction:column;gap:8px">
{hrow("Wallet","24 Oct 2026, 12:01 PM","Unlocked")}{hrow("Messages","24 Oct 2026, 12:00 PM","Wrong attempt")}
{hrow("Messages","24 Oct 2026, 12:00 PM","Wrong attempt")}{hrow("Gallery","24 Oct 2026, 11:42 AM","Timed unlock")}
{hrow("Browser","23 Oct 2026, 9:30 PM","Unlocked")}{hrow("Messages","23 Oct 2026, 9:12 PM","Wrong attempt")}
{hrow("Gallery","23 Oct 2026, 8:05 PM","Unlocked")}{hrow("Wallet","23 Oct 2026, 7:44 PM","Unlocked")}
{hrow("Messages","23 Oct 2026, 6:10 PM","Unlocked")}</div>"""
(shots/"06-history.html").write_text(page("Know every unlock","A private log of who got in and who didn't.",hist))

# ---------------------------------------------------------------- 07 vault
def cell(i):
    grads = ["linear-gradient(150deg,#2E3A5A,#18203A)","linear-gradient(200deg,#3A3358,#1B1A36)","linear-gradient(160deg,#24405A,#111F30)",
             "linear-gradient(175deg,#2A3556,#151B2E)","linear-gradient(190deg,#33305A,#1A1932)","linear-gradient(145deg,#2B3B52,#131E2C)"]
    return f'<div style="aspect-ratio:1;border-radius:16px;box-shadow:inset 0 0 0 1px rgba(255,255,255,.12);background:radial-gradient(42% 36% at 30% 28%,rgba(255,255,255,.16),rgba(255,255,255,0) 72%),{grads[i%6]}"></div>'
doc = f'<div class="glass" style="aspect-ratio:1;border-radius:16px;display:flex;align-items:center;justify-content:center;color:var(--onsv)">{ic("<path d=\"M13.5 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8.5z\"/><path d=\"M13.5 3v5.5H19\"/><path d=\"M8.5 13h7M8.5 16.5h4.5\"/>",28,1.7,extra="stroke=\"currentColor\"")}</div>'
vid = f'<div style="aspect-ratio:1;border-radius:16px;background:linear-gradient(140deg,#3E3A5A,#1F1A33);display:flex;align-items:center;justify-content:center;color:rgba(255,255,255,.8);box-shadow:inset 0 0 0 1px rgba(255,255,255,.12)">{ic("<rect x=\"2.5\" y=\"6\" width=\"13\" height=\"12\" rx=\"2.2\"/><path d=\"M15.5 11.2 21.5 8v8l-6-3.2z\"/>",28,1.7,extra="stroke=\"currentColor\"")}</div>'
cells = cell(0)+cell(1)+cell(2)+vid+cell(3)+cell(4)+doc+cell(5)+cell(0)+cell(2)+doc+cell(1)
vault = f"""<div style="padding:12px 20px 8px"><div class="hl">Vault</div><div class="bm onsv" style="margin-top:2px">12 items · AES-256-GCM, key held in secure hardware</div></div>
<div style="flex:1;position:relative;overflow:hidden;padding:12px 16px;display:grid;grid-template-columns:repeat(3,1fr);gap:8px;align-content:start">{cells}
<div style="position:absolute;right:16px;bottom:10px;height:56px;border-radius:28px;background:var(--acc);color:var(--onprim);display:flex;align-items:center;gap:8px;padding:0 22px;font-size:14px;font-weight:600;box-shadow:0 6px 16px rgba(0,0,0,.45)">{ic(I['plus'],20,2.2,extra='stroke="currentColor"')}Add</div></div>{tabs("Vault")}"""
(shots/"07-vault.html").write_text(page("A vault nothing else can read","Photos, video and files, encrypted with a hardware-held key.",vault))

# ---------------------------------------------------------------- 08 landscape-ready lock (pattern)
def node(on): 
    return (f'<div style="width:{18 if on else 14}px;height:{18 if on else 14}px;border-radius:50%;'
            f'border:{3 if on else 2}px solid {"var(--acc)" if on else "var(--out)"};'
            f'{"background:radial-gradient(circle,var(--acc) 35%,transparent 40%)" if on else ""};margin:auto"></div>')
sel = {0,1,2,5,8}
grid = "".join(f'<div style="display:flex">{node(i in sel)}</div>' for i in range(9))
pat = f"""<div style="flex:1;display:flex;flex-direction:column;align-items:center;padding-top:44px">
<div class="glass" style="width:72px;height:72px;border-radius:20px;display:flex;align-items:center;justify-content:center;color:var(--acc)">{ic(I['gallery'],44,1.5,extra='stroke="currentColor"')}</div>
<div class="tl" style="margin-top:18px;font-size:20px">Gallery</div>
<div class="bm onsv" style="margin-top:6px">Draw your pattern</div>
<div style="position:relative;margin-top:40px;width:260px;height:260px">
<svg width="260" height="260" style="position:absolute;inset:0"><polyline points="43,43 130,43 217,43 217,130 217,217" fill="none" stroke="#8CB8FF" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>
<div style="position:absolute;inset:0;display:grid;grid-template-columns:repeat(3,1fr);grid-template-rows:repeat(3,1fr)">{grid}</div></div>
<div class="lb acc" style="margin-top:36px">Close</div></div>"""
(shots/"08-pattern.html").write_text(page("Draw your own pattern","A pattern lock that fits any screen — portrait or landscape.",pat))
print("generated", len(list(shots.glob('*.html'))), "screens")
