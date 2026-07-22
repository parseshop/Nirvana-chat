<?php
/**
 * Nirvana SMS Messenger - cPanel Backend API
 * File: api.php
 * Usage: Place this file on your cPanel web hosting.
 */

error_reporting(E_ALL);
ini_set('display_errors', 0);
header('Content-Type: application/json; charset=utf-8');

// Initialize SQLite Database (creates database.sqlite if it doesn't exist)
$dbFile = __DIR__ . '/database.sqlite';
try {
    $db = new PDO("sqlite:" . $dbFile);
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    
    // Create installs table
    $db->exec("CREATE TABLE IF NOT EXISTS installs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        uuid TEXT UNIQUE,
        model TEXT,
        os_version TEXT,
        first_seen DATETIME DEFAULT CURRENT_TIMESTAMP,
        last_seen DATETIME DEFAULT CURRENT_TIMESTAMP
    )");

    // Create settings table
    $db->exec("CREATE TABLE IF NOT EXISTS settings (
        key TEXT UNIQUE,
        value TEXT
    )");

    // Create spam_rules table
    $db->exec("CREATE TABLE IF NOT EXISTS spam_rules (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        pattern TEXT UNIQUE,
        type TEXT,
        is_blacklist INTEGER DEFAULT 1
    )");

    // Seed default settings if empty
    $checkSettings = $db->query("SELECT COUNT(*) FROM settings")->fetchColumn();
    if ($checkSettings == 0) {
        $stmt = $db->prepare("INSERT INTO settings (key, value) VALUES (?, ?)");
        $stmt->execute(['promo_text', 'به پیام‌رسان پیشرفته و امن نیروانا خوش آمدید! 🌸']);
        $stmt->execute(['promo_url', 'https://ai.studio/build']);
    }

    // Seed some default remote spam words if empty
    $checkRules = $db->query("SELECT COUNT(*) FROM spam_rules")->fetchColumn();
    if ($checkRules == 0) {
        $defaultRules = [
            ['تور لحظه آخری', 'KEYWORD', 1],
            ['برنده آیفون', 'KEYWORD', 1],
            ['ثبت نام رایگان', 'KEYWORD', 1],
            ['افزایش درآمد', 'KEYWORD', 1],
            ['جایزه نقدی', 'KEYWORD', 1],
            ['کسب سود تضمینی', 'KEYWORD', 1],
        ];
        $stmt = $db->prepare("INSERT INTO spam_rules (pattern, type, is_blacklist) VALUES (?, ?, ?)");
        foreach ($defaultRules as $rule) {
            $stmt->execute($rule);
        }
    }

} catch (Exception $e) {
    echo json_encode(['success' => false, 'error' => 'Database error: ' . $e->getMessage()]);
    exit;
}

$action = isset($_GET['action']) ? $_GET['action'] : '';

switch ($action) {
    case 'ping':
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
            echo json_encode(['success' => false, 'error' => 'Only POST method is allowed for ping']);
            exit;
        }

        $uuid = isset($_POST['uuid']) ? trim($_POST['uuid']) : '';
        $model = isset($_POST['model']) ? trim($_POST['model']) : 'Unknown Device';
        $osVersion = isset($_POST['os_version']) ? trim($_POST['os_version']) : 'Unknown OS';

        if (empty($uuid)) {
            echo json_encode(['success' => false, 'error' => 'UUID is required']);
            exit;
        }

        try {
            // Check if uuid exists
            $stmt = $db->prepare("SELECT id FROM installs WHERE uuid = ?");
            $stmt->execute([$uuid]);
            $exists = $stmt->fetch();

            if ($exists) {
                // Update existing install record
                $stmt = $db->prepare("UPDATE installs SET model = ?, os_version = ?, last_seen = datetime('now', 'localtime') WHERE uuid = ?");
                $stmt->execute([$model, $osVersion, $uuid]);
            } else {
                // Register new installation
                $stmt = $db->prepare("INSERT INTO installs (uuid, model, os_version, first_seen, last_seen) VALUES (?, ?, ?, datetime('now', 'localtime'), datetime('now', 'localtime'))");
                $stmt->execute([$uuid, $model, $osVersion]);
            }

            echo json_encode(['success' => true, 'message' => 'Ping successfully registered']);
        } catch (Exception $e) {
            echo json_encode(['success' => false, 'error' => $e->getMessage()]);
        }
        break;

    case 'get_data':
        try {
            // Fetch promotional details
            $promoText = '';
            $promoUrl = '';

            $stmt = $db->prepare("SELECT value FROM settings WHERE key = ?");
            $stmt->execute(['promo_text']);
            $res = $stmt->fetch();
            if ($res) $promoText = $res['value'];

            $stmt->execute(['promo_url']);
            $res = $stmt->fetch();
            if ($res) $promoUrl = $res['value'];

            // Fetch spam rules list
            $rulesStmt = $db->query("SELECT pattern, type, is_blacklist FROM spam_rules");
            $rules = $rulesStmt->fetchAll(PDO::FETCH_ASSOC);

            // Structure response rules
            $formattedRules = [];
            foreach ($rules as $r) {
                $formattedRules[] = [
                    'pattern' => $r['pattern'],
                    'type' => $r['type'],
                    'is_blacklist' => (bool)$r['is_blacklist']
                ];
            }

            echo json_encode([
                'success' => true,
                'promo_text' => $promoText,
                'promo_url' => $promoUrl,
                'spam_rules' => $formattedRules
            ]);
        } catch (Exception $e) {
            echo json_encode(['success' => false, 'error' => $e->getMessage()]);
        }
        break;

    default:
        echo json_encode([
            'success' => false, 
            'error' => 'Invalid action', 
            'supported_actions' => ['ping (POST)', 'get_data (GET)']
        ]);
        break;
}
