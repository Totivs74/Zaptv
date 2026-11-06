# ZapTV

Fast IPTV player for Google TV (built for the Thomson Streaming Box Plus 270).

## Get the APK (no coding, about 10 minutes)

1. Sign in at https://github.com and click **New repository**. Name it `ZapTV`, choose **Public**, click **Create repository**.
2. On the new repo page click **uploading an existing file**.
3. Unzip `ZapTV.zip` on your computer, open the `ZapTV` folder, select **everything inside it** and drag it into the GitHub page. Click **Commit changes**.
   - Mac: Finder hides the `.github` folder. Press `Cmd + Shift + .` to show it before selecting.
4. Open the **Actions** tab. A build named "Build APK" starts by itself and takes about 5 to 10 minutes. Wait for the green tick.
5. Your APK is now always at:
   `https://github.com/YOUR-USERNAME/ZapTV/releases/latest/download/ZapTV.apk`

If the `.github` folder did not upload: Actions tab > "set up a workflow yourself", name it `build.yml`, paste the contents of `.github/workflows/build.yml`, commit.

## Install on the TV

1. On the TV, install **Downloader by AFTVnews** from the Play Store (search "aftvnews" if "downloader" finds nothing).
2. Open Downloader, type the APK link from step 5, press Go, then Install.
3. The first time, Android asks to allow Downloader to install apps: allow it.

Updating later: upload the changed files to GitHub, wait for the build, install the same link again. Settings and playlist are kept.

## First start

- The app shows an address and a QR code. Open it on your phone (same Wi-Fi) and enter an M3U link, Xtream Codes login, or MAC portal + MAC.
- For auto-start: Settings > Startup > allow "Display over other apps".
  If the TV hides that screen, run once from a computer: `adb shell appops set com.zaptv.app SYSTEM_ALERT_WINDOW allow`

## Remote

| In the player | |
|---|---|
| OK | Channel grid |
| Hold OK | Voice command (Turkish) |
| Up / Down | Next / previous channel |
| Left | Channel grid |
| Right | Audio, subtitles, picture size |
| 0 | Previous channel |
| 1-9 | Type a channel number |
| Back twice | Exit |

In the grid: OK opens a channel, the Guide and Settings buttons are at the top right. Colour keys and all of the above can be changed in Settings > Remote keys.

## Notes

- Audio: hardware decoding first, FFmpeg fallback for Dolby (AC-3 / E-AC-3), DTS and MP2.
- The FFmpeg module is GPL-3.0 licensed (Jellyfin build); fine for personal use.
- The app is a player only. Use playlists from providers licensed to distribute the channels.
