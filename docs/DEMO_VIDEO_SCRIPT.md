# PathShala: 2-minute demo video (challenge spec: ≤ 2:00, 1920×1080, 16:9, MP4, public link)

**How it is produced**
1. Record only the phone (about 110 s), in **airplane mode**: `scrcpy --no-audio --record phone.mp4`. Android's built-in screen recorder also works.
2. Record the voice-over separately while watching the clip (`voice.m4a`, about 2:00). Or skip it: the composer burns in captions.
3. `REPO=github.com/<you>/PathShala ./docs/video/make_demo.sh phone.mp4 voice.m4a` produces **PathShala_demo_1080p.mp4**: 5 s title card, then the phone on the left with a fact panel and timed captions on the right, then a 5 s end card. The output is H.264/AAC, 1920×1080, 30 fps.
4. Upload to YouTube and set it to **Public** (unlisted isn't enough), or to Google Drive with *Anyone with the link → Viewer*. Open the link in a private window to check.

**Before recording:** model loaded (badge shows "● Llama on-device"), app data reset (Goal → Reset), charger unplugged, benchmark already run once (so its results are on screen without waiting), Do Not Disturb on.

| Video time | Phone clip time | On screen | Voice-over (≈ 2.5 words/s) |
|---|---|---|---|
| 0:00–0:05 | — | Title card (automatic) | "PathShala: a learning companion that runs entirely on an Arm phone." |
| 0:05–0:13 | 0–8 s | Pull down quick settings: **airplane mode ON**; Engine badge "Llama on-device" | "Airplane mode is on. Llama 3.2 runs here through ExecuTorch on the Arm CPU." |
| 0:13–0:31 | 8–26 s | Goal tab → Hinglish example → *Understand* → card with "via llama" tags | "I type my goal in Hinglish. Llama turns it into structured data, and every field is checked, with a rule-based backup." |
| 0:31–0:57 | 26–52 s | Diagnostic: answer 5–6 questions quickly; uncertainty bar drops | "No fixed test. Each question is the most informative one for the whole skill graph, so it stops early." |
| 0:57–1:09 | 52–64 s | Skill map, tap one red node | "Red is a gap, green is mastered. Arrows are prerequisites." |
| 1:09–1:31 | 64–86 s | Plan: readiness → projected, reason chips; *Explain my plan* streams | "The plan ranks topics by exam value per hour. Every block says why, and Llama only narrates facts the engine computed." |
| 1:31–1:45 | 86–100 s | Practice: *Need a hint?* → wrong answer → mastery % changes → *Next day ▶* shows review blocks | "Hints never give the answer away. Each answer updates my mastery, and spaced reviews appear before I forget." |
| 1:45–1:55 | 100–110 s | Engine: benchmark card (tok/s, cores busy, W) → scroll to **Permissions: none** | "Here's the real speed and power on this phone. And the app asks for zero permissions." |
| 1:55–2:00 | — | End card (automatic) | "Llama for language, algorithms for decisions." |

**If the phone section runs long:** cut the skill-map tap first, then shorten the diagnostic to 4 answers. Edit `CAPS=(…)` in `make_demo.sh` if your timings differ.
