<?php
/**
 * Nirvana SMS Messenger - cPanel Web Admin Dashboard
 * File: index.php
 * Usage: Place this file alongside api.php on cPanel.
 */

session_start();

// Admin Configuration
$admin_password = 'admin'; // Change this to your desired password!

$dbFile = __DIR__ . '/database.sqlite';
try {
    $db = new PDO("sqlite:" . $dbFile);
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
} catch (Exception $e) {
    die("Database Connection Error: " . $e->getMessage());
}

// Handle Login
$error = '';
if (isset($_POST['login'])) {
    $pass = isset($_POST['password']) ? $_POST['password'] : '';
    if ($pass === $admin_password) {
        $_SESSION['logged_in'] = true;
        header("Location: index.php");
        exit;
    } else {
        $error = 'رمز عبور وارد شده نادرست است!';
    }
}

// Handle Logout
if (isset($_GET['logout'])) {
    session_destroy();
    header("Location: index.php");
    exit;
}

// If not logged in, display login screen
if (!isset($_SESSION['logged_in']) || $_SESSION['logged_in'] !== true) {
?>
<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ورود به پنل مدیریت نیروانا</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link href="https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@v33.003/Vazirmatn-font-face.css" rel="stylesheet" type="text/css" />
    <style>
        body { font-family: 'Vazirmatn', sans-serif; }
    </style>
</head>
<body class="bg-slate-950 text-slate-100 flex items-center justify-center min-h-screen p-4">
    <div class="w-full max-w-md bg-slate-900 border border-slate-800 rounded-2xl shadow-2xl p-8">
        <div class="text-center mb-8">
            <div class="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-indigo-600/20 text-indigo-400 mb-4">
                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-8 h-8">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M16.5 10.5V6.75a4.5 4.5 0 1 0-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 0 0 2.25-2.25v-6.75a2.25 2.25 0 0 0-2.25-2.25H6.75a2.25 2.25 0 0 0-2.25 2.25v6.75a2.25 2.25 0 0 0 2.25 2.25Z" />
                </svg>
            </div>
            <h1 class="text-2xl font-bold tracking-tight">پنل مدیریت برنامه نیروانا</h1>
            <p class="text-sm text-slate-400 mt-2">برای مدیریت تبلیغات و مشاهده آمار وارد شوید</p>
        </div>

        <?php if ($error): ?>
            <div class="bg-red-950/40 border border-red-900/50 text-red-400 px-4 py-3 rounded-xl mb-6 text-sm text-center">
                <?php echo htmlspecialchars($error); ?>
            </div>
        <?php endif; ?>

        <form method="POST" action="">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm font-medium text-slate-300 mb-2">رمز عبور مدیریت</label>
                    <input type="password" name="password" required placeholder="••••••••" 
                           class="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-3 text-slate-100 placeholder-slate-600 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 transition-all text-center">
                </div>
                <button type="submit" name="login" 
                        class="w-full bg-indigo-600 hover:bg-indigo-500 active:bg-indigo-700 text-white font-medium rounded-xl py-3 transition-colors shadow-lg shadow-indigo-600/20">
                    ورود به پنل
                </button>
            </div>
        </form>
        
        <div class="mt-8 text-center text-xs text-slate-500">
            طراحی شده برای هماهنگی کامل با نرم‌افزار ضدتبلیغ نیروانا
        </div>
    </div>
</body>
</html>
<?php
    exit;
}

// Handle ad settings update
$msg = '';
if (isset($_POST['update_promo'])) {
    $promo_text = isset($_POST['promo_text']) ? trim($_POST['promo_text']) : '';
    $promo_url = isset($_POST['promo_url']) ? trim($_POST['promo_url']) : '';
    
    try {
        $stmt = $db->prepare("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)");
        $stmt->execute(['promo_text', $promo_text]);
        $stmt->execute(['promo_url', $promo_url]);
        $msg = 'تنظیمات تبلیغ درون‌برنامه‌ای با موفقیت بروزرسانی شد.';
    } catch (Exception $e) {
        $msg = 'خطا در بروزرسانی تنظیمات: ' . $e->getMessage();
    }
}

// Handle spam rule creation
if (isset($_POST['add_rule'])) {
    $pattern = isset($_POST['pattern']) ? trim($_POST['pattern']) : '';
    $type = isset($_POST['type']) ? trim($_POST['type']) : 'KEYWORD';
    
    if (!empty($pattern)) {
        try {
            $stmt = $db->prepare("INSERT OR IGNORE INTO spam_rules (pattern, type, is_blacklist) VALUES (?, ?, 1)");
            $stmt->execute([$pattern, $type]);
            $msg = 'قانون فیلترینگ جدید با موفقیت اضافه شد.';
        } catch (Exception $e) {
            $msg = 'خطا در افزودن قانون: ' . $e->getMessage();
        }
    }
}

// Handle rule deletion
if (isset($_GET['delete_rule'])) {
    $rule_id = intval($_GET['delete_rule']);
    try {
        $stmt = $db->prepare("DELETE FROM spam_rules WHERE id = ?");
        $stmt->execute([$rule_id]);
        header("Location: index.php?msg=" . urlencode("قانون با موفقیت حذف شد."));
        exit;
    } catch (Exception $e) {
        $msg = 'خطا در حذف قانون: ' . $e->getMessage();
    }
}

if (isset($_GET['msg'])) {
    $msg = $_GET['msg'];
}

// Fetch stats
$totalInstalls = $db->query("SELECT COUNT(*) FROM installs")->fetchColumn();
$active24h = $db->query("SELECT COUNT(*) FROM installs WHERE last_seen >= datetime('now', '-1 day', 'localtime')")->fetchColumn();
$active7d = $db->query("SELECT COUNT(*) FROM installs WHERE last_seen >= datetime('now', '-7 days', 'localtime')")->fetchColumn();

// Fetch settings
$stmt = $db->prepare("SELECT value FROM settings WHERE key = ?");
$stmt->execute(['promo_text']);
$promo_text = $stmt->fetchColumn() ?: '';

$stmt->execute(['promo_url']);
$promo_url = $stmt->fetchColumn() ?: '';

// Fetch all installs
$installs = $db->query("SELECT * FROM installs ORDER BY last_seen DESC")->fetchAll(PDO::FETCH_ASSOC);

// Fetch all spam rules
$rules = $db->query("SELECT * FROM spam_rules ORDER BY id DESC")->fetchAll(PDO::FETCH_ASSOC);
?>
<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>پنل مدیریت و کنترل تبلیغات نیروانا</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link href="https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@v33.003/Vazirmatn-font-face.css" rel="stylesheet" type="text/css" />
    <style>
        body { font-family: 'Vazirmatn', sans-serif; }
    </style>
</head>
<body class="bg-slate-950 text-slate-100 min-h-screen">
    
    <!-- Navbar -->
    <header class="border-b border-slate-900 bg-slate-900/50 backdrop-blur sticky top-0 z-50">
        <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
            <div class="flex items-center gap-3">
                <div class="w-10 h-10 rounded-xl bg-indigo-600/20 text-indigo-400 flex items-center justify-center">
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-6 h-6">
                        <path stroke-linecap="round" stroke-linejoin="round" d="M10.5 6h9.75M10.5 6a1.5 1.5 0 1 1-3 0m3 0a1.5 1.5 0 1 0-3 0M3.75 6H7.5m3 12h9.75m-9.75 0a1.5 1.5 0 0 1-3 0m3 0a1.5 1.5 0 0 0-3 0m-3.75 0H7.5m9-6h3.75m-3.75 0a1.5 1.5 0 0 1-3 0m3 0a1.5 1.5 0 0 0-3 0m-9.75 0h9.75" />
                    </svg>
                </div>
                <div>
                    <h1 class="text-md font-bold text-slate-100">پنل کنترل مرکزی نیروانا</h1>
                    <p class="text-xs text-slate-400">آمار نصب، تبلیغات و فیلترینگ پیامک</p>
                </div>
            </div>
            
            <a href="?logout=1" class="text-sm bg-slate-800 hover:bg-red-950/30 hover:text-red-400 text-slate-300 px-4 py-2 rounded-xl border border-slate-800 hover:border-red-900/50 transition-all flex items-center gap-2">
                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-4 h-4">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 9V5.25A2.25 2.25 0 0 0 13.5 3h-6a2.25 2.25 0 0 0-2.25 2.25v13.5A2.25 2.25 0 0 0 7.5 21h6a2.25 2.25 0 0 0 2.25-2.25V15M12 9l-3 3m0 0 3 3m-3-3h12.75" />
                </svg>
                خروج
            </a>
        </div>
    </header>

    <main class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-8">
        
        <?php if ($msg): ?>
            <div class="bg-indigo-950/40 border border-indigo-900/50 text-indigo-300 px-5 py-4 rounded-2xl text-sm flex items-center justify-between">
                <span><?php echo htmlspecialchars($msg); ?></span>
                <button onclick="this.parentElement.remove()" class="text-slate-400 hover:text-white">✕</button>
            </div>
        <?php endif; ?>

        <!-- Stats Overview -->
        <section class="grid grid-cols-1 md:grid-cols-3 gap-6">
            <div class="bg-slate-900/50 border border-slate-900 rounded-2xl p-6">
                <div class="flex items-center justify-between">
                    <span class="text-sm text-slate-400 font-medium">کل کاربران نصب‌کننده</span>
                    <span class="w-8 h-8 rounded-lg bg-indigo-600/10 text-indigo-400 flex items-center justify-center">
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5">
                            <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 6a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0ZM4.501 20.118a7.5 7.5 0 0 1 14.998 0A17.933 17.933 0 0 1 12 21.75c-2.676 0-5.216-.584-7.499-1.632Z" />
                        </svg>
                    </span>
                </div>
                <div class="mt-4 flex items-baseline gap-2">
                    <span class="text-3xl font-extrabold tracking-tight"><?php echo number_format($totalInstalls); ?></span>
                    <span class="text-xs text-slate-500 font-medium">دستگاه فعال</span>
                </div>
            </div>

            <div class="bg-slate-900/50 border border-slate-900 rounded-2xl p-6">
                <div class="flex items-center justify-between">
                    <span class="text-sm text-slate-400 font-medium">فعال ۲۴ ساعت گذشته</span>
                    <span class="w-8 h-8 rounded-lg bg-green-600/10 text-green-400 flex items-center justify-center">
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5">
                            <path stroke-linecap="round" stroke-linejoin="round" d="M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75z" />
                        </svg>
                    </span>
                </div>
                <div class="mt-4 flex items-baseline gap-2">
                    <span class="text-3xl font-extrabold tracking-tight text-green-400"><?php echo number_format($active24h); ?></span>
                    <span class="text-xs text-slate-500 font-medium">دستگاه آنلاین</span>
                </div>
            </div>

            <div class="bg-slate-900/50 border border-slate-900 rounded-2xl p-6">
                <div class="flex items-center justify-between">
                    <span class="text-sm text-slate-400 font-medium">فعال ۷ روز گذشته</span>
                    <span class="w-8 h-8 rounded-lg bg-sky-600/10 text-sky-400 flex items-center justify-center">
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5">
                            <path stroke-linecap="round" stroke-linejoin="round" d="M6.75 3v2.25M17.25 3v2.25M3 18.75V7.5a2.25 2.25 0 0 1 2.25-2.25h13.5A2.25 2.25 0 0 1 21 7.5v11.25m-18 0A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75m-18 0v-7.5A2.25 2.25 0 0 1 5.25 9h13.5A2.25 2.25 0 0 1 21 11.25v7.5m-9-6h.008v.008H12v-.008zM12 15h.008v.008H12V15zm0 2.25h.008v.008H12v-.008zM9.75 15h.008v.008H9.75V15zm0 2.25h.008v.008H9.75v-.008zM7.5 15h.008v.008H7.5V15zm0-2.25h.008v.008H7.5v-.008zm6.75 4.5h.008v.008h-.008v-.008zm0-2.25h.008v.008h-.008V15zm0-2.25h.008v.008h-.008v-.008zm2.25 4.5h.008v.008H16.5v-.008zm0-2.25h.008v.008H16.5V15z" />
                        </svg>
                    </span>
                </div>
                <div class="mt-4 flex items-baseline gap-2">
                    <span class="text-3xl font-extrabold tracking-tight"><?php echo number_format($active7d); ?></span>
                    <span class="text-xs text-slate-500 font-medium">دستگاه تعاملی</span>
                </div>
            </div>
        </section>

        <!-- Two Columns: Ad Settings & Custom Filters -->
        <section class="grid grid-cols-1 lg:grid-cols-2 gap-8">
            
            <!-- Column 1: Ad Configuration -->
            <div class="bg-slate-900/40 border border-slate-900 rounded-2xl p-6 space-y-6">
                <div class="flex items-center gap-2 border-b border-slate-900 pb-4">
                    <div class="w-8 h-8 rounded-lg bg-indigo-600/10 text-indigo-400 flex items-center justify-center">
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5">
                            <path stroke-linecap="round" stroke-linejoin="round" d="M10.34 15.84c-.68-.69-.68-1.8 0-2.49l3.03-3.03M13.37 10.32c.68.69.68 1.8 0 2.49l-3.03 3.03m-2.2-2.18l-3.03-3.03a1.8 1.8 0 1 1 2.54-2.54l3.03 3.03m0 0l3.03 3.03a1.8 1.8 0 1 1-2.54 2.54l-3.03-3.03Z" />
                        </svg>
                    </div>
                    <h2 class="text-lg font-bold">مدیریت تبلیغ درون‌برنامه‌ای (بنر)</h2>
                </div>
                
                <form method="POST" action="" class="space-y-4">
                    <div>
                        <label class="block text-sm font-medium text-slate-300 mb-2">متن بنر تبلیغاتی</label>
                        <textarea name="promo_text" rows="3" required class="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-3 text-slate-100 placeholder-slate-600 focus:outline-none focus:ring-2 focus:ring-indigo-500 transition-all text-sm leading-relaxed"><?php echo htmlspecialchars($promo_text); ?></textarea>
                    </div>
                    
                    <div>
                        <label class="block text-sm font-medium text-slate-300 mb-2">لینک بنر تبلیغاتی</label>
                        <input type="url" name="promo_url" value="<?php echo htmlspecialchars($promo_url); ?>" required class="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-3 text-slate-100 placeholder-slate-600 focus:outline-none focus:ring-2 focus:ring-indigo-500 transition-all text-sm select-all">
                    </div>
                    
                    <button type="submit" name="update_promo" class="w-full bg-indigo-600 hover:bg-indigo-500 active:bg-indigo-700 text-white font-medium rounded-xl py-3 text-sm transition-colors shadow-lg shadow-indigo-600/15">
                        بروزرسانی تبلیغ
                    </button>
                </form>
            </div>

            <!-- Column 2: Anti-Spam / Filter Rules -->
            <div class="bg-slate-900/40 border border-slate-900 rounded-2xl p-6 space-y-6">
                <div class="flex items-center gap-2 border-b border-slate-900 pb-4">
                    <div class="w-8 h-8 rounded-lg bg-indigo-600/10 text-indigo-400 flex items-center justify-center">
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5">
                            <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m0-10.036A11.959 11.959 0 0 1 3.598 6 11.99 11.99 0 0 0 3 9.75c0 5.592 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.57-.598-3.75h-.152c-3.196 0-6.1-1.249-8.25-3.286Zm0 13.036h.008v.008H12v-.008Z" />
                        </svg>
                    </div>
                    <h2 class="text-lg font-bold">افزودن قانون ضدتبلیغات جدید (بروزرسانی زنده)</h2>
                </div>

                <form method="POST" action="" class="space-y-4">
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label class="block text-sm font-medium text-slate-300 mb-2">نوع قانون فیلتر</label>
                            <select name="type" class="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-3 text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500 transition-all text-sm">
                                <option value="KEYWORD">کلمه کلیدی در متن پیامک</option>
                                <option value="SENDER">شماره فرستنده (پیش‌شماره یا شماره کامل)</option>
                            </select>
                        </div>
                        <div>
                            <label class="block text-sm font-medium text-slate-300 mb-2">کلمه یا شماره مورد نظر</label>
                            <input type="text" name="pattern" placeholder="مثال: تور کیش، 5000" required class="w-full bg-slate-950 border border-slate-800 rounded-xl px-4 py-3 text-slate-100 placeholder-slate-600 focus:outline-none focus:ring-2 focus:ring-indigo-500 transition-all text-sm">
                        </div>
                    </div>

                    <button type="submit" name="add_rule" class="w-full bg-indigo-600 hover:bg-indigo-500 active:bg-indigo-700 text-white font-medium rounded-xl py-3 text-sm transition-colors shadow-lg shadow-indigo-600/15">
                        افزودن و فیلتر کردن سراسری
                    </button>
                </form>
            </div>
        </section>

        <!-- Dynamic Filter Rules List -->
        <section class="bg-slate-900/40 border border-slate-900 rounded-2xl p-6">
            <div class="flex items-center justify-between border-b border-slate-900 pb-4 mb-6">
                <div class="flex items-center gap-2">
                    <div class="w-8 h-8 rounded-lg bg-indigo-600/10 text-indigo-400 flex items-center justify-center">
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5">
                            <path stroke-linecap="round" stroke-linejoin="round" d="M3.75 5.25h16.5m-16.5 4.5h16.5m-16.5 4.5h16.5m-16.5 4.5h16.5" />
                        </svg>
                    </div>
                    <h2 class="text-lg font-bold">قوانین فیلترینگ فعال روی برنامه ها</h2>
                </div>
                <span class="text-xs bg-slate-800 px-3 py-1.5 rounded-full text-slate-400 border border-slate-800">
                    تعداد: <?php echo count($rules); ?> قانون
                </span>
            </div>

            <div class="overflow-x-auto">
                <?php if (empty($rules)): ?>
                    <p class="text-sm text-slate-500 text-center py-6">هیچ قانون شخصی‌سازی شده‌ای ثبت نشده است.</p>
                <?php else: ?>
                    <table class="w-full text-sm text-right">
                        <thead>
                            <tr class="border-b border-slate-800 text-slate-400 text-xs">
                                <th class="pb-3 pr-2">شناسه</th>
                                <th class="pb-3">الگو / کلمه یا شماره</th>
                                <th class="pb-3">نوع فیلتر</th>
                                <th class="pb-3">اقدام</th>
                            </tr>
                        </thead>
                        <tbody class="divide-y divide-slate-900">
                            <?php foreach ($rules as $index => $r): ?>
                                <tr class="text-slate-300">
                                    <td class="py-3.5 pr-2 font-mono text-slate-500"><?php echo $r['id']; ?></td>
                                    <td class="py-3.5 font-medium text-indigo-200"><?php echo htmlspecialchars($r['pattern']); ?></td>
                                    <td class="py-3.5">
                                        <?php if ($r['type'] === 'KEYWORD'): ?>
                                            <span class="bg-blue-500/10 text-blue-400 text-xs px-2.5 py-1 rounded-full border border-blue-500/20">کلمه کلیدی</span>
                                        <?php else: ?>
                                            <span class="bg-amber-500/10 text-amber-400 text-xs px-2.5 py-1 rounded-full border border-amber-500/20">فرستنده</span>
                                        <?php endif; ?>
                                    </td>
                                    <td class="py-3.5">
                                        <a href="?delete_rule=<?php echo $r['id']; ?>" onclick="return confirm('آیا از حذف این قانون فیلترینگ مطمئن هستید؟')" class="text-xs text-red-400 hover:text-red-300 hover:underline">حذف قانون</a>
                                    </td>
                                </tr>
                            <?php endforeach; ?>
                        </tbody>
                    </table>
                <?php endif; ?>
            </div>
        </section>

        <!-- Install Statistics list -->
        <section class="bg-slate-900/40 border border-slate-900 rounded-2xl p-6">
            <div class="flex items-center justify-between border-b border-slate-900 pb-4 mb-6">
                <div class="flex items-center gap-2">
                    <div class="w-8 h-8 rounded-lg bg-indigo-600/10 text-indigo-400 flex items-center justify-center">
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5">
                            <path stroke-linecap="round" stroke-linejoin="round" d="M9 17.25v1.007a3 3 0 0 1-.879 2.122L7.5 21h9l-.621-.621A3 3 0 0 1 15 18.257V17.25m6-12V15a2.25 2.25 0 0 1-2.25 2.25H5.25A2.25 2.25 0 0 1 3 15V5.25m18 0A2.25 2.25 0 0 0 18.75 3H5.25A2.25 2.25 0 0 0 3 5.25m18 0V12a2.25 2.25 0 0 1-2.25 2.25H5.25A2.25 2.25 0 0 1 3 12V5.25" />
                        </svg>
                    </div>
                    <h2 class="text-lg font-bold">دستگاه‌های نصب کننده فعال برنامه</h2>
                </div>
                <span class="text-xs bg-slate-800 px-3 py-1.5 rounded-full text-slate-400 border border-slate-800">
                    تعداد دستگاه: <?php echo count($installs); ?> مورد
                </span>
            </div>

            <div class="overflow-x-auto">
                <?php if (empty($installs)): ?>
                    <p class="text-sm text-slate-500 text-center py-6">هنوز هیچ نصب فعالی ثبت نشده است.</p>
                <?php else: ?>
                    <table class="w-full text-sm text-right">
                        <thead>
                            <tr class="border-b border-slate-800 text-slate-400 text-xs">
                                <th class="pb-3 pr-2">ردیف</th>
                                <th class="pb-3">مدل دستگاه</th>
                                <th class="pb-3 font-mono">UUID دستگاه</th>
                                <th class="pb-3">نسخه سیستم‌عامل</th>
                                <th class="pb-3">اولین نصب</th>
                                <th class="pb-3">آخرین فعالیت</th>
                            </tr>
                        </thead>
                        <tbody class="divide-y divide-slate-900">
                            <?php foreach ($installs as $index => $inst): ?>
                                <tr class="text-slate-300">
                                    <td class="py-3.5 pr-2 font-mono text-slate-500"><?php echo $index + 1; ?></td>
                                    <td class="py-3.5 font-semibold text-slate-100"><?php echo htmlspecialchars($inst['model']); ?></td>
                                    <td class="py-3.5 font-mono text-xs text-slate-400 select-all"><?php echo htmlspecialchars($inst['uuid']); ?></td>
                                    <td class="py-3.5 text-indigo-300"><?php echo htmlspecialchars($inst['os_version']); ?></td>
                                    <td class="py-3.5 text-xs text-slate-400"><?php echo htmlspecialchars($inst['first_seen']); ?></td>
                                    <td class="py-3.5 text-xs text-green-400 font-medium"><?php echo htmlspecialchars($inst['last_seen']); ?></td>
                                </tr>
                            <?php endforeach; ?>
                        </tbody>
                    </table>
                <?php endif; ?>
            </div>
        </section>

    </main>

    <footer class="mt-20 border-t border-slate-900 py-8 text-center text-xs text-slate-500">
        &copy; <?php echo date('Y'); ?> سیستم کنترل مرکزی ضد تبلیغ نیروانا - همه حقوق محفوظ است.
    </footer>

</body>
</html>
