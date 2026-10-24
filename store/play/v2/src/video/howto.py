#!/usr/bin/env python3
"""Builds the how-to video: one 1920x1080 HTML frame per UI state + a timeline of taps.

usage: howto.py <workdir>   (render_video.sh calls this, then screenshots each frame with Chrome,
                              then calls tap_positions to fill in coordinates)
Each state's [data-tap] elements are located by Chrome itself (see render_video.sh), so the tap
ripples land on the real element positions instead of hand-measured numbers.
"""
import json, sys
from pathlib import Path

here = Path(__file__).parent
src = here.parent
sys.path.insert(0, str(src))
import gen  # noqa: E402  (reuses the screen components; also regenerates shots/)

work = Path(sys.argv[1]); work.mkdir(parents=True, exist_ok=True)
css = (src / "shots" / "shared.css").resolve()
ic, I, tabs, STATUS = gen.ic, gen.I, gen.tabs, gen.STATUS

FRAME_CSS = """
html,body{width:1920px;height:1080px;overflow:hidden}
.bd{position:absolute;inset:0;background:
 radial-gradient(1100px 1100px at 6% 0%,rgba(46,91,255,.46),rgba(46,91,255,0) 70%),
 radial-gradient(1200px 1200px at 100% 100%,rgba(138,63,252,.38),rgba(138,63,252,0) 70%),#0A0E1A}
.brand{position:absolute;left:120px;top:76px;display:flex;align-items:center;gap:14px;font-size:28px;font-weight:700;letter-spacing:-.5px;color:#DDE4F5}
.txt{position:absolute;left:120px;top:0;bottom:0;width:900px;display:flex;flex-direction:column;justify-content:center}
.step{font-size:26px;font-weight:700;letter-spacing:3px;color:#8CB8FF;margin-bottom:22px}
.txt h2{margin:0;font-size:78px;line-height:1.05;font-weight:700;letter-spacing:-2.6px;color:#F7F9FF;text-wrap:balance}
.txt p{margin:28px 0 0;font-size:38px;line-height:1.32;color:#AEB7CC;letter-spacing:-.3px;max-width:780px;text-wrap:balance}
.ph{position:absolute;left:1150px;top:60px;width:600px;height:960px;border-radius:44px;overflow:hidden;
 border:1px solid rgba(255,255,255,.16);box-shadow:0 40px 100px rgba(0,0,0,.6)}
.ph .ui{zoom:1.4599}
"""
TAPJS = """<script>addEventListener('load',()=>{const o={};document.querySelectorAll('[data-tap]').forEach(e=>{
const r=e.getBoundingClientRect();o[e.dataset.tap]=[Math.round(r.left+r.width/2),Math.round(r.top+r.height/2)]});
const p=document.createElement('pre');p.id='taps';p.textContent='@@'+JSON.stringify(o)+'@@';document.body.appendChild(p);
p.style.cssText='position:absolute;left:-9999px'})</script>"""

def frame(step, title, text, body):
    return f"""<!doctype html><meta charset="utf-8"><link rel="stylesheet" href="file://{css}">
<style>{FRAME_CSS}</style><div class="bd"></div>
<div class="brand">{BRAND}AppLock · How to use</div>
<div class="txt"><div class="step">{step}</div><h2>{title}</h2><p>{text}</p></div>
<div class="ph"><div class="ui">{STATUS}{body}</div></div>{TAPJS}"""

BRAND = """<svg width="40" height="40" viewBox="20 16 68 76"><defs><linearGradient id="g" gradientUnits="userSpaceOnUse" x1="27" y1="24" x2="81" y2="86"><stop offset="0" stop-color="#F0D79A"/><stop offset=".45" stop-color="#C79A46"/><stop offset="1" stop-color="#8C6A2C"/></linearGradient></defs><path d="M54,24 L81,33.5 L81,54.5 C81,70 69.5,80.5 54,86.5 C38.5,80.5 27,70 27,54.5 L27,33.5 Z" fill="url(#g)"/><circle cx="54" cy="44" r="6.5" fill="#0E1116"/><path d="M50.6,49 L57.4,49 L59.4,66 L48.6,66 Z" fill="#0E1116"/></svg>"""

# ------------------------------------------------------------------ components
def tp(name): return f' data-tap="{name}"'
def scrim(inner): return f'<div style="position:absolute;inset:0;background:rgba(0,0,0,.62);display:flex;align-items:center;justify-content:center">{inner}</div>'
def card(inner, w=310): return f'<div style="width:{w}px;background:var(--dlg);border-radius:28px;padding:22px">{inner}</div>'
def abtn(label, name=None, primary=False):
    t = tp(name) if name else ""
    if primary:
        return f'<div{t} style="flex:1;height:44px;border-radius:12px;background:var(--acc);color:var(--onprim);display:flex;align-items:center;justify-content:center;font-size:14px;font-weight:600">{label}</div>'
    return f'<div{t} style="flex:1;height:44px;border-radius:12px;border:1px solid var(--out);color:var(--acc);display:flex;align-items:center;justify-content:center;font-size:14px;font-weight:600">{label}</div>'
def pt(h, d): return f'<div style="margin-bottom:8px"><div class="ts">{h}</div><div class="bs onsv" style="margin-top:1px">{d}</div></div>'

def perm_row(title, why, granted, required, tap):
    mark = (f'<div style="width:20px;height:20px;border-radius:50%;background:var(--acc);display:flex;align-items:center;justify-content:center">{ic("<path d=\"M6 12.5l4 4 8-9\"/>",14,3,extra="stroke=\"#06080D\"")}</div>'
            if granted else f'<div style="width:20px;height:20px;border-radius:50%;border:2px solid {"var(--err)" if required else "var(--out)"}"></div>')
    badge = '' if (granted or not required) else '<span style="margin-left:8px;font-size:10px;font-weight:600;letter-spacing:.6px;color:var(--err);background:rgba(255,138,128,.18);padding:2px 6px;border-radius:6px">REQUIRED</span>'
    return f"""<div{tp(tap)} class="glass" style="border-radius:14px;padding:14px;display:flex;gap:12px;margin-bottom:10px;{'' if granted or not required else 'box-shadow:inset 0 0 0 1px rgba(255,138,128,.5)'}">
<div style="padding-top:2px">{mark}</div><div style="flex:1"><div class="tm" style="display:flex;align-items:center">{title}{badge}</div><div class="bm onsv" style="margin-top:2px">{why}</div></div></div>"""

def perms(c1, c2, done):
    d = ('<div data-tap="done" style="height:52px;border-radius:14px;background:var(--acc);color:var(--onprim);display:flex;align-items:center;justify-content:center;font-size:14px;font-weight:600">Done</div>'
         if done else '<div style="height:52px;border-radius:14px;background:rgba(255,255,255,.1);color:rgba(255,255,255,.35);display:flex;align-items:center;justify-content:center;font-size:14px;font-weight:600">Done</div>')
    return f"""<div style="flex:1;overflow:hidden;padding:14px 24px">
<div style="text-align:center"><div style="font-size:34px;font-weight:700;letter-spacing:-.8px;line-height:1.1">Two switches to flip</div>
<div class="bm onsv" style="margin:8px 0 18px">AppLock can't lock anything without these, and Android only lets you grant them by hand.</div></div>
{perm_row("Detect the app in front","Lets AppLock notice the moment a protected app opens. It reads only the app's name — never what's on your screen.",c1,True,"perm1")}
{perm_row("Display over other apps","This is the permission the lock screen is drawn with. Without it AppLock cannot ask for your PIN.",c2,True,"perm2")}
{perm_row("Notifications","Shows the small badge confirming protection is running, and intruder alerts.",False,False,"perm3")}
<div style="margin-top:12px">{d}</div></div>"""

def dlg_access():
    inner = f"""<div style="display:flex;align-items:center;gap:10px"><div style="color:var(--acc)">{ic('<circle cx="12" cy="5" r="2"/><path d="M5 9h14M12 9v12M9 21l3-6 3 6"/>',26,1.8,extra='stroke="currentColor"')}</div><div class="tl" style="font-size:18px">AppLock uses the Accessibility API</div></div>
<div class="bs onsv" style="margin:12px 0">To put a lock screen in front of the apps you choose, AppLock has to know which app you have just opened.</div>
{pt("What it reads","Only the package name of the app that comes to the foreground — and nothing else.")}
{pt("What it cannot do","It cannot read your screen, your messages, or anything you type.")}
{pt("Where it goes","Nowhere. Nothing is recorded, shared or sent off your phone.")}
<div style="display:flex;gap:10px;margin-top:14px">{abtn("No thanks")}{abtn("I agree","agree1",True)}</div>"""
    return scrim(card(inner, 330))

def dlg_overlay():
    inner = f"""<div style="display:flex;align-items:center;gap:10px"><div style="color:var(--acc)">{ic(gen.I['lock'],26,1.8,extra='stroke="currentColor"')}</div><div class="tl" style="font-size:18px">Display over other apps</div></div>
<div class="bs onsv" style="margin:12px 0">To ask for your PIN in front of a protected app, AppLock draws its lock screen on top of other apps.</div>
{pt("What it reads","Nothing. It only lets AppLock show a window.")}
{pt("What it cannot do","It cannot see, record or change what other apps display.")}
<div style="display:flex;gap:10px;margin-top:14px">{abtn("No thanks")}{abtn("I agree","agree2",True)}</div>"""
    return scrim(card(inner, 330))

def apps_screen(rows, extra="", sub="Everything private stays private"):
    return gen.header_block(sub) + f'<div style="flex:1;overflow:hidden;padding:4px 16px 0;display:flex;flex-direction:column;gap:10px">{extra}{rows}</div>' + tabs("Apps")

def row(name, glyph, sub, on, tap, subcls="acc"):
    return gen.app_row(name, glyph, sub, on, subcls).replace('<div class="glass"', f'<div class="glass"{tp(tap)}', 1)

def rows_all_off():
    return (row("Gallery","gallery","Not locked",False,"r_gallery","onsv") + row("Messages","chat","Not locked",False,"r_messages","onsv")
            + row("Wallet","wallet","Not locked",False,"r_wallet","onsv") + row("Browser","globe","Not locked",False,"r_browser","onsv"))

def dlg_setup():
    def opt(t, d, hl, tap=None):
        return f'<div{tp(tap) if tap else ""} style="border-radius:16px;padding:12px;margin-bottom:10px;{"background:rgba(140,184,255,.14);box-shadow:inset 0 0 0 1px var(--acc)" if hl else "box-shadow:inset 0 0 0 1px var(--out)"}"><div class="ts">{t}</div><div class="bs onsv" style="margin-top:2px">{d}</div></div>'
    inner = f"""<div class="tl" style="font-size:19px">How should AppLock check it's you?</div>
<div class="bs onsv" style="margin:8px 0 14px">Messages is on your locked list now, but AppLock has nothing to ask for yet. Pick one — you'll only be asked once.</div>
{opt("Set an AppLock PIN or pattern","A separate secret, just for locked apps.",True,"opt_pin")}
{opt("Use my phone's screen lock","Use the lock you already have on this phone.",False)}
<div style="text-align:center;margin-top:6px;font-size:14px;font-weight:500;color:var(--acc)">Not now</div>"""
    return scrim(card(inner, 330))

def keypad(): 
    ks = ""
    for c in "123456789": ks += f'<div{tp("k"+c)} class="glass" style="border-radius:26px;height:62px;display:flex;align-items:center;justify-content:center;font-size:26px;font-weight:300">{c}</div>'
    ks += f'<div style="display:flex;align-items:center;justify-content:center;color:var(--acc)">{ic(I["finger"],30,1.6,extra="stroke=\"currentColor\"")}</div><div class="glass" style="border-radius:26px;height:62px;display:flex;align-items:center;justify-content:center;font-size:26px;font-weight:300">0</div><div style="display:flex;align-items:center;justify-content:center;color:var(--onsv)">{ic(I["backsp"],24,1.7,extra="stroke=\"currentColor\"")}</div>'
    return f'<div style="margin-top:22px;width:330px;display:grid;grid-template-columns:repeat(3,1fr);gap:10px">{ks}</div>'

def lock_screen(title, hint, filled, glyph="chat"):
    return f"""<div style="flex:1;display:flex;flex-direction:column;align-items:center;padding-top:34px">
<div class="glass" style="width:68px;height:68px;border-radius:20px;display:flex;align-items:center;justify-content:center;color:var(--acc)">{ic(I[glyph],40,1.5,extra='stroke="currentColor"')}</div>
<div class="tl" style="margin-top:14px;font-size:20px">{title}</div><div class="bm onsv" style="margin-top:4px">{hint}</div>
<div style="margin-top:20px">{gen.dots(4,filled)}</div>{keypad()}</div>"""

def chat_app():
    def b(t, me=False):
        return f'<div style="align-self:{"flex-end" if me else "flex-start"};max-width:240px;padding:10px 14px;border-radius:18px;background:{"#2E5BFF" if me else "rgba(255,255,255,.14)"};font-size:14px;line-height:19px">{t}</div>'
    return f"""<div style="padding:10px 20px;display:flex;align-items:center;gap:10px"><div class="tl">Messages</div></div>
<div style="flex:1;padding:8px 20px;display:flex;flex-direction:column;gap:10px">{b("Are we still on for dinner?")}{b("Yes! 7:30 works.",True)}{b("Great, see you there.")}</div>
<div style="padding:10px 20px 18px"><div class="glass" style="height:44px;border-radius:22px;display:flex;align-items:center;padding:0 16px;font-size:14px;color:var(--onsv)">Message</div></div>"""

def dlg_timed():
    def b(t, tap=None): return f'<div{tp(tap) if tap else ""} style="padding:10px 0;font-size:14px;font-weight:500;color:var(--acc)">{t}</div>'
    inner = f"""<div class="tl">Unlock Messages for…</div>
<div class="bm onsv" style="margin-top:10px">It opens without a PIN until the time is up, even if you leave it or turn the screen off.</div>
<div style="margin-top:6px">{b("15 minutes")}{b("30 minutes","t30")}{b("1 hour")}{b("2 hours")}</div>
<div style="text-align:right;margin-top:4px;font-size:14px;font-weight:500;color:var(--acc)">Cancel</div>"""
    return scrim(card(inner, 300))

def profile_chips(names, active=None, savetap=False):
    ch = "".join(f'<div class="pill{" sel" if n == active else ""}">{n}</div>' for n in names)
    return f'<div style="display:flex;gap:8px;align-items:center">{ch}<div{tp("saveprof") if savetap else ""} class="pill" style="background:rgba(140,184,255,.14);color:var(--acc)">{ic(I["plus"],15,2,extra="stroke=\"currentColor\"")}Save as profile</div></div>'

def dlg_name():
    inner = f"""<div class="tl" style="font-size:19px">Save 6 locked apps</div>
<div class="glass" style="margin:14px 0;height:50px;border-radius:12px;display:flex;align-items:center;padding:0 14px;font-size:16px">Work<span style="width:2px;height:20px;background:var(--acc);margin-left:2px"></span></div>
<div style="display:flex;justify-content:flex-end;gap:22px;font-size:14px;font-weight:500;color:var(--acc)"><span>Cancel</span><span{tp("savebtn")}>Save</span></div>"""
    return scrim(card(inner, 300))

def intruder_grid():
    t = lambda i, w, tap="": gen.tile(i, w).replace('<div style="position:relative', f'<div{tp(tap) if tap else ""} style="position:relative', 1)
    return f"""<div style="padding:12px 20px 8px"><div class="hl">Intruders</div><div class="bm onsv" style="margin-top:2px">6 photos · deleted after 30 days</div></div>
<div style="flex:1;overflow:hidden;padding:12px 16px 0;display:grid;grid-template-columns:1fr 1fr;gap:10px;align-content:start">
{t(0,"24 Oct, 12:00 PM","tile1")}{t(1,"24 Oct, 11:58 AM")}{t(2,"23 Oct, 9:12 PM")}{t(3,"23 Oct, 9:11 PM")}</div>{tabs("Intruders")}"""

def viewer(n, tap_area=True):
    return f"""<div style="position:absolute;inset:0;background:#000">
<div style="position:absolute;inset:0;top:60px;bottom:60px"{tp("photo")}>{gen.silhouette(n)}</div>
<div style="position:absolute;left:0;right:0;top:24px;padding:10px 14px;display:flex;align-items:center;gap:12px;background:linear-gradient(rgba(0,0,0,.6),transparent)">
<div style="color:#fff">{ic('<path d="M6 6l12 12M18 6L6 18"/>',24,1.8,extra='stroke="currentColor"')}</div>
<div style="flex:1"><div class="ts" style="color:#fff">Messages</div><div class="ls" style="color:rgba(255,255,255,.75)">24 Oct, 12:00 PM · 6 failed attempts</div></div>
<div style="color:#fff">{ic('<path d="M9 4h6l-1 6 3 3H7l3-3zM12 13v7"/>',22,1.7,extra='stroke="currentColor"')}</div></div>
<div style="position:absolute;bottom:22px;left:0;right:0;text-align:center"><span class="lb" style="background:rgba(255,255,255,.16);color:#fff;padding:6px 14px;border-radius:50px">{n+1} / 5</span></div></div>"""

# ------------------------------------------------------------------ states
S = []   # (name, step, title, text, body, duration, events)
def st(name, step, title, text, body, dur, events=()):
    S.append((name, step, title, text, body, dur, list(events)))

T1 = ("STEP 1 OF 7", "Allow the permissions", "AppLock explains each one first. Tap a row, read it, then agree.")
st("1a", *T1, perms(False, False, False), 2.4, [("tap", "perm1", 1.1)])
st("1b", *T1, perms(False, False, False) + dlg_access(), 3.6, [("tap", "agree1", 2.4)])
st("1c", *T1, perms(True, False, False), 2.2, [("tap", "perm2", 1.0)])
st("1d", *T1, perms(True, False, False) + dlg_overlay(), 3.0, [("tap", "agree2", 1.9)])
st("1e", *T1, perms(True, True, True), 2.2, [("tap", "done", 1.1)])

T2 = ("STEP 2 OF 7", "Pick the apps to lock", "Flip the switch next to any app. Suggestions can lock several at once.")
st("2a", *T2, apps_screen(rows_all_off()), 2.8, [("tap", "r_messages", 1.3)])
st("2b", *T2, apps_screen(row("Messages","chat","Locked",True,"x1") + row("Gallery","gallery","Not locked",False,"x2","onsv")) + dlg_setup(), 3.2, [("tap", "opt_pin", 2.0)])

T3 = ("STEP 3 OF 7", "Create your PIN", "Choose a PIN or a pattern. You'll enter it twice to confirm.")
st("3a", *T3, lock_screen("New unlock", "Choose a 4-digit PIN", 0), 1.6, [("tap", "k1", 0.7)])
st("3b", *T3, lock_screen("New unlock", "Choose a 4-digit PIN", 1), 1.0, [("tap", "k2", 0.4)])
st("3c", *T3, lock_screen("New unlock", "Choose a 4-digit PIN", 2), 1.0, [("tap", "k3", 0.4)])
st("3d", *T3, lock_screen("New unlock", "Choose a 4-digit PIN", 3), 1.0, [("tap", "k4", 0.4)])
st("3e", *T3, lock_screen("New unlock", "Enter it again to confirm", 0), 2.0, [("tap", "k1", 0.5), ("tap", "k2", 0.9), ("tap", "k3", 1.3), ("tap", "k4", 1.7)])

T4 = ("STEP 4 OF 7", "Open a locked app", "AppLock appears first. Enter your PIN, or use your fingerprint.")
st("4a", *T4, lock_screen("Messages", "Enter your PIN", 0), 2.0, [("tap", "k1", 0.6), ("tap", "k2", 1.0), ("tap", "k3", 1.4), ("tap", "k4", 1.8)])
st("4b", *T4, lock_screen("Messages", "Enter your PIN", 4), 0.9)
st("4c", *T4, chat_app(), 2.6)

T5 = ("STEP 5 OF 7", "Unlock for a while", "Press and hold a locked app, then choose 15 minutes to 2 hours.")
locked_rows = row("Gallery","gallery","Locked",True,"y1") + row("Messages","chat","Locked",True,"y_msg") + row("Wallet","wallet","Locked",True,"y3")
st("5a", *T5, apps_screen(locked_rows), 2.8, [("hold", "y_msg", 1.2)])
st("5b", *T5, apps_screen(locked_rows) + dlg_timed(), 3.2, [("tap", "t30", 2.0)])
st("5c", *T5, apps_screen(row("Gallery","gallery","Locked",True,"z1") + row("Messages","chat","Unlocked for 30 more min",True,"z2") + row("Wallet","wallet","Locked",True,"z3")), 2.6)

T6 = ("STEP 6 OF 7", "Save a profile", "Save your locked apps as Work, Home or Kids, and switch with one tap.")
prof_extra = lambda chips: f'{gen.stats("6","14","3")}{chips}'
st("6a", *T6, apps_screen(row("Gallery","gallery","Locked",True,"w1"), prof_extra(profile_chips([], None, True))), 2.6, [("tap", "saveprof", 1.2)])
st("6b", *T6, apps_screen(row("Gallery","gallery","Locked",True,"w1"), prof_extra(profile_chips([], None, True))) + dlg_name(), 3.0, [("tap", "savebtn", 2.0)])
st("6c", *T6, apps_screen(row("Gallery","gallery","Locked",True,"w1"), prof_extra(profile_chips(["Work"], "Work"))), 2.4)

T7 = ("STEP 7 OF 7", "See who tried to get in", "After repeated wrong attempts AppLock takes a photo. Swipe through them.")
st("7a", *T7, intruder_grid(), 2.8, [("tap", "tile1", 1.3)])
st("7b", *T7, viewer(0), 3.0, [("swipe", "photo", 1.2)])
st("7c", *T7, viewer(1), 2.0)

st("8a", "AND MORE", "Make it yours", "Timed unlock, schedules, a fake crash screen, five themes, an encrypted vault and uninstall protection.",
   gen.settings + tabs("Settings"), 4.0)

# ------------------------------------------------------------------ title / outro frames
def center(body): return f"""<!doctype html><meta charset="utf-8"><link rel="stylesheet" href="file://{css}"><style>{FRAME_CSS}
.c{{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center}}
.c h1{{margin:30px 0 0;font-size:132px;line-height:1.02;font-weight:700;letter-spacing:-4.5px;color:#F7F9FF}}
.c p{{margin:24px 0 0;font-size:46px;color:#B5BED3;letter-spacing:-.4px}}
.chips{{display:flex;gap:16px;margin-top:46px}}.chip{{font-size:28px;color:#E6EBF7;border:1px solid rgba(255,255,255,.22);border-radius:999px;padding:14px 26px;background:rgba(255,255,255,.1)}}
</style><div class="bd"></div><div class="c">{body}</div>"""
shield = BRAND.replace('width="40" height="40"', 'width="200" height="200"')
intro = center(f'{shield}<h1>How to use AppLock</h1><p>Set up in under a minute.</p>')
outro = center(f'{shield}<h1>You\'re protected</h1><p>Private. Offline. No ads. No account.</p>'
               '<div class="chips"><div class="chip">Timed unlock</div><div class="chip">Lock profiles</div><div class="chip">Intruder selfie</div><div class="chip">Encrypted vault</div></div>')

frames = [("00-intro", intro, 3.2, [])]
for name, step, title, text, body, dur, ev in S:
    frames.append((f"{name}", frame(step, title, text, body), dur, ev))
frames.append(("99-outro", outro, 3.6, []))
(work / "frames").mkdir(exist_ok=True)
meta = []
for n, html, dur, ev in frames:
    (work / "frames" / f"{n}.html").write_text(html)
    meta.append({"name": n, "dur": dur, "events": [{"type": t, "target": tg, "at": at} for t, tg, at in ev]})
(work / "meta.json").write_text(json.dumps(meta))
print(len(frames), "frames")
