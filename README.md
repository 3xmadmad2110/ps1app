# PS1App — اپ مادر پلیاستیشن 1 + پنل سازنده بازی

این پروژه شامل سه بخش است:

1. **اپ مادر اندروید** (`duckstation/overlay/` + سورس standalone DuckStation) با پکیج پایه `com.farsipsx.ps1app` که از **فرانتاند واقعی DuckStation** (نه SwanStation و نه libretro) برای اجرای فایلهای `bin/cue` استفاده میکند.
2. **پنل مدیریتی وب** (`panel/`) که فایل `bin`، `cue` و کاور بازی را دریافت میکند، فایل **OBB** حاوی دیتا میسازد و ساخت APK را به GitHub Actions میسپارد.
3. **ریلیز GitHub** که در نهایت حاوی `APK` مخصوص همان بازی + `OBB` است.

> **نکته قانونی:** از این ابزار فقط برای ایمجها و BIOSهایی استفاده کنید که حقوق قانونی آنها را دارید. این پروژه خود هیچ فایل بازی یا BIOSای توزیع نمیکند؛ فایلهای BIOS توسط کاربر در ریلیز جداگانه `share-bios` قرار میگیرند.

---

## ساختار پوشهها

```
ps1app/
├── panel/                     # پنل مدیریتی (Node.js، بدون وابستگی خارجی)
│   ├── server.js              # سرور HTTP و مسیرها
│   ├── builder.js             # ساخت OBB، ریلیز و dispatch
│   ├── github.js              # کلاینت REST API گیتهاب
│   └── zip.js                 # نویسنده ZIP/OBB بدون کتابخانه
├── duckstation/overlay/       # لایه اختصاصی PS1App روی سورس DuckStation
│   └── app/
│       ├── build.gradle       # applicationId دینامیک + URLهای دانلود
│       ├── src/main/AndroidManifest.xml
│       ├── src/main/java/com/github/stenzek/duckstation/
│       │   ├── GameLauncherActivity.java   # صفحه «دریافت دیتا» / «اجرای بازی»
│       │   ├── DataDownloader.java         # دانلود resumable با مکث/ادامه/لغو
│       │   ├── BiosInstaller.java          # نصب همه BIOSها در DuckStation
│       │   └── ObbData.java                # خواندن meta.json و استخراج bin/cue
│       └── src/main/res/      # رابط کاربری فارسی
├── android/                   # پروژه قدیمی libretro (دیگر استفاده نمیشود)
├── .github/workflows/build-game.yml
├── panel-data/                # فایلهای لحظهای (gitignore)
└── .env.example
```

سورس شبیهساز در Actions از فورک GPL-3.0 `gfhhhg/duckstation-android` (کمیت `dcc1e5d`) گرفته میشود که مبتنی بر آخرین کامیت upstream دارای فرانتاند اندروید (`81da9be`, 2021-06-07) است.

---

## نصب و راهاندازی

### 1. پیشنیازها

- **Node.js 20+** برای پنل
- **GitHub PAT** با scopes: `repo` (و `workflow` در صورت نیاز)
- ساخت APK فقط از طریق GitHub Actions انجام میشود (SDK/NDK محلی لازم نیست)

### 2. تنظیمات پنل

```powershell
Copy-Item .env.example .env
notepad .env
```

فیلدهای مهم:

| متغیر | توضیح |
|---|---|
| `GITHUB_TOKEN` | PATnew با نام `ps1app` |
| `GITHUB_OWNER` / `GITHUB_REPO` | `3xmadmad2110` / `ps1app` |
| `GITHUB_WORKFLOW` | `build-game.yml` |
| `PORT` | پورت پنل، پیشفرض `7788` |
| `VERSION_CODE` | حتماً با نسخه OBB یکسان باشد |

### 3. اجرای پنل

```powershell
npm start
```

سپس باز کنید: <http://localhost:7788>

### 4. Secretهای امضاء در GitHub

| Secret | توضیح |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | keystorenew با alias `ps1app` بهصورت Base64 |
| `KEYSTORE_PASSWORD` | رمز keystore |
| `KEY_ALIAS` | `ps1app` |
| `KEY_PASSWORD` | رمز کلید |

### 5. ریلیزBIOS مشترک

ریلیز `share-bios` حاوی asset واحد `bios-pack.zip` است. اپ هنگام «دریافت دیتا» این فایل را هم دانلود و تمام BIOSهای داخل آنرا با فراخوانی `importBIOSImage()` بومی DuckStation در مسیر `/sdcard/duckstation/bios/` نصب میکند.

---

## نحوه کار

1. در پنل نام بازی و پسوند پکیج را وارد و فایل `bin`، `cue` و کاور را آپلود کنید.
2. پنل OBB را (شامل bin، cue، کاور و `meta.json`) میسازد، ریلیز `ps1-<slug>-v1` را ایجاد و workflow `build-game.yml` را dispatch میکند.
3. Actions سورس standalone DuckStation را میگیرد، لایه PS1App را روی آن اعمال میکند، آیکون را از کاور میسازد، APK را امضاء و به همان ریلیز آپلود میکند.
4. پنل وضعیت را چک میکند و لینک APK و OBB را نشان میدهد.

---

## نصب توسط کاربر نهایی

1. APK مخصوص بازی را نصب کنید.
2. اپ را باز کنید و دکمه **«دریافت دیتا»** را بزنید.
3. اپ ابتدا OBB (~700MB) و سپس `bios-pack.zip` را دانلود میکند. درصد، حجم دریافتشده، حجم کل و سرعت لحظهای نمایش داده میشود.
4. دکمه **«مکث» / «ادامه»** و **«لغو»** در دسترس است و دانلود در صورت قطع اتصال از همان نقطه ادامه مییابد (HTTP Range).
5. بعد از اتمام، BIOSها نصب، فایل `bin/cue` از OBB استخراج و دکمه **«اجرای بازی»** فعال میشود. با زدن آن، `EmulationActivity` از DuckStation اجرا میشود.
6. دکمه **«تنظیمات»** وارد رابط تنظیمات DuckStation (شامل چیدمان دکمهها و تنظیمات گرافیکی) میشود.

---

## جزئیات فنی

- `applicationId` توسط `-PpackageSuffix` به `com.farsipsx.ps1app.<slug>` تغییر میکند، در حالی که پکیج Java سورس `com.github.stenzek.duckstation` باقی میماند.
- URL دانلود OBB و BIOS توسط `resValue` درون APK قرار میگیرند.
- شبیهساز شامل کتابخانه بومی `libduckstation-native.so` برای `arm64-v8a` و `armeabi-v7a` است (برخلاف اپ قدیمی که کور را در زمان اجرا بارگذاری میکرد).
- DuckStation واقعی فاقد HLE BIOS است؛ بههمین دلیل BIOSها از ریلیز `share-bios` دانلود و توسط متد بومی `importBIOSImage` با هش IEEE CRC محل مناسب نصب میشوند.

---

## عیبیابی

| مشکل | راهحل |
|---|---|
| پیام Missing BIOS | بررسی کنید `bios-pack.zip` در ریلیز `share-bios` موجود باشد و دانلود دوم کامل شود |
| `OBB پیدا نشد` | نام OBB باید دقیقاً `main.<versionCode>.<package>.obb` باشد |
| ریلیز draft میماند | لاگ workflow را در تب Actions ببینید؛ معمولاً نبود Secret امضاء است |
| آپلود OBB شکست خورد | محدودیت معمول GitHub Release حدود 2GB است |

---

## دستورات مفید

```powershell
# بررسی سلامت پنل
curl http://localhost:7788/api/jobs

# ارسال دستی ساخت APK
# (پنل این کار را انجام میدهد)
```
