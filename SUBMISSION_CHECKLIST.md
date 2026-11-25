# Submission checklist: Bharat AI-SoC Challenge, Phase 1 (deadline 25 Nov 2026, 11:59 pm IST)

**You can submit the form only once, and only the first submission counts.** Finish every item below before you open the form.

## A. Phone run (≈ 2 h): Jash / Chaitanya / Harsh
- [ ] Export or download the model: `model/export_llama.sh` (16 GB RAM laptop, Hugging Face account with the Llama 3.2 licence accepted).
- [ ] `adb install -r app-debug.apk`, then `model/push_to_phone.sh <pte> tokenizer.model`, then Engine → Scan & load. The badge should read "● Llama on-device".
- [ ] Run one full flow: goal, diagnostic, plan, Explain my plan, practice with a hint, tutor.
- [ ] Unplug the charger, close other apps, Engine → **Run 3 PathShala workloads**. Save `adb logcat -s PathShala/Bench > bench.txt`.
- [ ] Fill the **Section 6.2** table in the report (tokens, TTFT, prefill/decode tok/s, cores busy, threads, RSS, W, J/100 tok, device line, load time, thermal state, diagnostic step and replan ms). Commit `bench.txt` to `eval/device/`.

## B. Demo video (≤ 2:00, 1920×1080, 16:9, MP4)
- [ ] Record the phone in airplane mode following `docs/DEMO_VIDEO_SCRIPT.md`: `scrcpy --no-audio --record phone.mp4` (about 110 s).
- [ ] Record the voice-over (`voice.m4a`), optional.
- [ ] `REPO=github.com/<you>/PathShala ./docs/video/make_demo.sh phone.mp4 voice.m4a` → `PathShala_demo_1080p.mp4`. Check it reports 1920×1080 and ≤ 120 s.
- [ ] Upload to YouTube and set visibility to **Public**. Open the link in an incognito window to confirm.

## C. Report (.pdf)
- [ ] In the doc: add the faculty mentor name, the GitHub link, the video link and the Section 6.2 numbers. Export to PDF and replace `docs/PathShala_Technical_Report.pdf`.
- [ ] Check it still covers methodology (Sections 3–5), results (Section 7), hardware utilisation (6.2) and optimisation techniques (6.1).

## D. Repository
- [ ] Create a new GitHub repo, then `git init && git add . && git commit -m "PathShala Phase 1" && git push`.
- [ ] Settings → set the repo to **Public**. Open it in incognito to confirm.
- [ ] Put the video link and the report link at the top of `README.md`.
- [ ] Optional but useful: attach `app-debug.apk` to a GitHub Release (the APK is not committed; `.gitignore` excludes build outputs).
- [ ] Faculty mentor reviews the repo and report and approves (recommended by the organisers).

## E. Form: https://forms.gle/3KyscquPFG8rS9ASA (one shot)
- [ ] Repo link (public) · video link (public) · report PDF upload · team details as registered · Problem Statement 4.
- [ ] Re-read every field, then submit **once**, ideally by the evening of 25 Nov rather than near midnight.
