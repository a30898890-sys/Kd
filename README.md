# KD — Phase 1 (UNBUILT, UNVERIFIED)

Status: source written but NOT yet compiled or tested. The first GitHub build is the first real compile.

## Build from your phone (GitHub Actions)
1. Install **Termux from F-Droid** (not Play Store). Open it and run:
   pkg install git unzip
   termux-setup-storage
2. Unzip (adjust path to where the zip was downloaded):
   cd ~ && unzip ~/storage/downloads/kd-phase1.zip && cd kd
3. Create an empty repo named `kd` on github.com (browser). Create a token at
   github.com/settings/tokens (classic, scope: repo, workflow).
4. Push:
   git init -b main && git add . && git commit -m "KD phase 1"
   git remote add origin https://github.com/YOUR_USER/kd.git
   git push -u origin main      (username = your GitHub name, password = the token)
5. Repo > Actions > "Build KD APK". Takes ~5 min. If red, open the failed step,
   copy the error text and send it to Claude.
6. Green: open the run > Artifacts > KD-debug-apk (downloads a zip containing app-debug.apk).
   Extract, tap the APK, allow "install unknown apps" for your browser/file manager.

The APK is signed with the standard Android debug key: installable, not Play Store ready.

## Phase 1 test checklist (on your phone)
- App opens; Bangla text renders.
- Type "কেডি, ক্রোম খোলো" > Chrome opens.
- Type "কেডি, গুগলে AI agent সম্পর্কে সার্চ করো" > Google results open.
- Type "এখন কয়টা বাজে" > time shown/spoken.
- Tap 🎤, allow mic, say the same commands. Note the exact text KD shows as "আপনি:".
- Log says whether Bangla TTS voice exists.
- Phone says unsupported (not success) for: call, reminder, flashlight, back.
