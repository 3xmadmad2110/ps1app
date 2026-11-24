# PS1App — اپ مادر پلیاستیشن 1 + پنل سازنده بازی

این پروژه شامل سه بخش است:

1. **اپ مادر اندروید** (`android/`) با پکیج پایه `com.farsipsx.ps1app` که از کور **SwanStation** (DuckStation / libretro) برای اجرای فایلهای `bin/cue` استفاده میکند.
2. **پنل مدیریتی وب** (`panel/`) که فایل `bin`، `cue` و کاور بازی را دریافت میکند، فایل **OBB** حاوی دیتا میسازد و ساخت APK را به GitHub Actions میسپارد.
3. **ریلیز GitHub** که در نهایت حاوی `APK` مخصوص همان بازی + `OBB` است.

> **نکته قانونی:** از این ابزار فقط برای ایمجهایی استفاده کنید که حقوق قانونی آنها را دارید. این پروژه یک شبیهساز (emulator) است و هیچ فایل بازیای را توزیع نمیکند.

---

## ساختار پوشهها

```
ps1app/
├── panel/                     # پنل مدیریتی (Node.js، بدون وابستگی خارجی)
│   ├── server.js              # سرور HTTP و مسیرها
│   ├── multipart.js           # آپلود جریانی فایلهای بزرگ
│   ├── builder.js             # ساخت OBB، ریلیز و dispatch
│   ├── github.js              # کلاینت REST API گیتهاب
│   ├── zip.js                 # نویسنده ZIP/OBB بدون کتابخانه
│   ├── views.js + public/     # رابط کاربری RTL
│   └── ...
├── android/                   # پروژه Android (Kotlin + C++)
│   └── app/src/main/
│       ├── java/com/farsipsx/ps1app/
│       ├── cpp/               # فرانتاند libretro (EGL/GLES2 + AudioTrack)
│       └── res/
├── scripts/
│   ├── fetch-core.*           # دانلود کور pcsx_rearmed
│   ├── make-icons.sh          # ساخت آیکون از کاور در Actions
│   └── gen-icons.js           # آیکونهای پیشفرض
├── .github/workflows/build-game.yml
├── panel-data/                # فایلهای لحظهای (ساخته میشود، gitignore)
└── .env.example
```

---

## نصب و راهاندازی

### 1. پیشنیازها

- **Node.js 20+** برای پنل (در محیط شما Node 26 موجود است)
- **Java 17** + **Android SDK** + **NDK 25.2.9519653** فقط برای ساخت محلی
- یک مخزن GitHub و یک **Personal Access Token** با scopes: `repo` (و `workflow` در صورت نیاز)

### 2. تنظیمات پنل

```powershell
Copy-Item .env.example .env
notepad .env
```

فیلدهای مهم:

| متغیر | توضیح |
|---|---|
| `GITHUB_TOKEN` | PAT با دسترسی نوشتن به ریلیزها |
| `GITHUB_OWNER` | نام کاربری یا سازمان |
| `GITHUB_REPO` | نام مخزن (همین پروژه را در آن قرار دهید) |
| `PORT` | پورت پنل، پیشفرض `7788` |
| `BUILD_MODE` | `actions` (پیشفرض) یا `local` |
| `VERSION_CODE` | نسخه اپ؛ حتماً با نسخه OBB یکسان باشد |

### 3. اجرای پنل

```powershell
npm start
# یا
node panel/server.js
```

سپس باز کنید: <http://localhost:7788>

اگر GitHub تنظیم نشده باشد، پنل در **حالت محلی** کار میکند و فقط فایل OBB را آماده میکند (برای تست).

### 4. تنظیم GitHub Actions

1. پوشه `ps1app` را به مخزن(Git) بفرستید.
2. در مخزن: **Settings → Secrets and variables → Actions** موارد زیر را اضافه کنید:

| Secret | توضیح |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | فایل keystore امضا بهصورت Base64 (اختیاری) |
| `KEYSTORE_PASSWORD` | رمز keystore |
| `KEY_ALIAS` | نام کلید (پیشفرض `ps1app`) |
| `KEY_PASSWORD` | رمز کلید |

3. فایل `.github/workflows/build-game.yml` را در شاخه `main` (یا مقدار `GITHUB_REF`) قرار دهید.
4. مخزن باید اجازه **workflow_dispatch** را داشتهباشد (در تنظیمات Actions → General).

### 5. دانلود کور پلیاستیشن (برای ساخت محلی)

در حالت `actions` این کار توسط workflow انجام میشود. برای ساخت روی دستگاه خود:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/fetch-core.ps1
# یا
node scripts/gen-icons.js     # آیکونهای پیشفرض (در مخزن موجود است)
```

کور در مسیر `android/app/src/main/jniLibs/<abi>/libpcsx_rearmed.so` قرار میگیرد و درون APK بستهبندی میشود.

---

## نحوه کار

1. در پنل، نام بازی (مثلاً `God of War`) و optionally پسوند پکیج را وارد کنید.
2. فایل `disc.bin` (یا `.img`) و `disc.cue` و کاور را آپلود کنید.
   - نام فایلها داخل OBB **دقیقاً همان نامی میماند که آپلود کردید**، بنابراین خط `FILE "disc.bin" BINARY` درون CUE معتبر میماند.
3. پنل:
   - MIME و postfix فایلها را بررسی میکند.
   - اسلاگ را میسازد: `God of War` → `god_of_war` (اسامی فارسی هم به لاتین تبدیل میشوند).
   - پکیج نهایی را میسازد: `com.farsipsx.ps1app.god_of_war`
   - فایل `main.1.com.farsipsx.ps1app.god_of_war.obb` را بهصورت ZIP میسازد (شامل bin، cue، کاور و `meta.json`).
   - یک **draft release** با تگ `ps1-god_of_war-v1` ایجاد و OBB + کاور را آپلود میکند.
   - workflow `build-game.yml` را با پارامترها dispatch میکند.
4. GitHub Actions:
   - کور pcsx_rearmed را دانلود میکند.
   - آیکون اپ را از کاور بازی میسازد.
   - APK را با `applicationId` و نام بازی مربوطه میسازد.
   - APK را به همان ریلیز آپلود و ریلیز را publish میکند.
5. پنل هر ۸ ثانیه وضعیت را چک میکند و لینک دانلود APK و OBB را نشان میدهد.

---

## نصب توسط کاربر نهایی

1. فایل APK مخصوص بازی را نصب کنید.
2. اپ را باز کنید و دکمه **«دریافت دیتا»** را بزنید. اپ فایل OBB را مستقیم از ریلیز GitHub دانلود و در پوشه مخصوص خود قرار میدهد؛ نیازی به کپی دستی فایل نیست.
3. در پنجره دانلود، درصد پیشرفت، حجم دریافتشده، حجم کل و سرعت لحظهای نمایش داده میشود. دکمه **«مکث»** و **«ادامه»** برای کنترل دانلود در دسترس است.
4. اگر اتصال قطع شود یا دانلود ناقص بماند، اپ بهطور خودکار از همان نقطه ادامه میدهد؛ در دفعات بعد هم دکمه **«ادامه دانلود»** نمایش داده میشود.
5. بعد از اتمام دانلود، دکمه **«اجرای بازی»** فعال میشود. با زدن آن، دیتا استخراج شده و شبیهساز SwanStation آغاز به کار میکند.

اگر دیتا قبلاً بهطور کامل دانلود شده باشد، اپ بهطور خودکار آن را پیدا میکند و فقط دکمه «اجرای بازی» را نشان میدهد.

---

## جزئیات فنی اپ مادر

- `applicationId` توسط پارامتر `-PpackageSuffix` تغییر میکند، در حالی که `namespace` کد (`com.farsipsx.ps1app`) ثابت میماند. بههمین دلیل تمام بازیها از یک کد منبع مشترک ساخته میشوند.
- نام نمایشی اپ توسط `resValue("string", "app_name", ...)` از پنل تزریق میشود.
- فرانتاند C++ تنها از ABI عمومی libretro استفاده میکند و کور را در زمان اجرا با `dlopen` بارگذاری میکند؛ بنابراین جایگزینی کور (مثلاً `swanstation`) بدون تغییر کد اپ ممکن است.
- تصویر توسط EGL/GLES2 روی `SurfaceView` رندر میشود، صدا توسط `AudioTrack` در سمت کاتلین پخش میشود و ورودیها با دکمههای لمسی و کیبورد فیزیکی پشتیبانی میشوند.

---

## عیبیابی

| مشکل | راهحل |
|---|---|
| پیام `core missing` | اسکریپت `fetch-core` را اجرا کنید یا بررسی کنید کور در jniLibs قرار گرفته است |
| `OBB پیدا نشد` | نام فایل و پوشه را دقیقاً مطابق الگوی بالا تنظیم کنید |
| ریلیز draft میماند | لاگ workflow را در تب Actions ببینید؛ معمولاً نبود Secret امضا یا مشکل دانلود کور است |
| آپلود فایل big حجم شکست خورد | حد GitHub Release معمولاً 2GB است؛ در `server.js` همین محدودیت اعمال شده |
| ساخت محلی کار نمیکند | Android SDK/NDK نصب و `ANDROID_HOME` تنظیم شود؛ سپس پنل را با `BUILD_MODE=local` اجرا کنید |

---

## دستورات مفید

```powershell
# تست سریع پنل بدون GitHub
$env:BUILD_MODE='local'; node panel/server.js

# ساخت دستی یک APK
cd android
.\gradlew.bat :app:assembleRelease -PgameSlug=god_of_war -PgameName="God of War" -PpackageSuffix=god_of_war -PversionCode=1

# بررسی سلامت پنل
curl http://localhost:7788/health
```
