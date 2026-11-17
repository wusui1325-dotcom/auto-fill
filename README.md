# 填碼（auto-fill）

原生 Android 小工具：在網頁或 App 的驗證碼欄位被選取，或你手動開始監看後，監看通知。驗證碼一來就半屏詢問是否填入；你點「填入」才會寫入欄位，不會把完整驗證碼留在剪貼簿，也不會讀取簡訊收件匣。監看不會自動關閉。

顯示名稱：填碼  
applicationId：`com.wusper.auto.fill`  
介面預設繁體中文，另有简体中文與 English。Material 3 / Material You 動態色彩。目前發行版本：0.1.3。

## 行為

- 進入驗證碼欄位，或在主畫面按「開始監看」後，才會解析通知。監看會一直持續到你按「停止監看」或關掉主開關，不會因離開頁面、切換 App 或等候而自動關閉。
- 欄位判斷：`autofillHints`（smsOTP / emailOTP 等）、數字輸入、提示文字含「驗證碼／验证码／OTP／verification code」，或 4–8 位長度。
- 關掉主開關或按「停止監看」，就停止監看。
- 確認畫面只顯示抽出的驗證碼與簡短來源（App 名稱或短標題），不顯示完整簡訊或郵件本文。
- 填入失敗時不做其他動作，你可以手動輸入。
- 不連網、無分析、不讀 SMS Provider。
- 0.1.2 起加強逐格（一格一位）驗證碼框填入：支援換行排列與輕微錯位，必要時只把單一字符暫放剪貼簿再立刻還原，不會把完整驗證碼寫入剪貼簿。
- 主畫面按鈕可進入獨立的內建驗證器頁面：離線產生 6 位 TOTP（otpauth 預設 SHA1／30 秒，也可讀取 SHA256／SHA512），密鑰以系統 Keystore 加密存放在本應用，支援匯入與匯出（可選密碼）。可掃描或匯入 otpauth 與 Google Authenticator 匯出（otpauth-migration）二維碼或圖片。填入仍需半屏確認。相機權限只在掃描時才會申請。
- 0.1.3 起：點「填入」後會先收起確認畫面再重新定位網頁／App 欄位（含 Cloudflare Access 等 WebView），並依序嘗試 SET_TEXT、暫時貼上（隨即還原剪貼簿）與逐字輸入。

## 權限

- **通知存取**：只在監看期間從通知抽出驗證碼。
- **無障礙**：只用於偵測驗證碼欄位、顯示確認畫面，以及在你確認後把驗證碼寫入該欄位。優先使用無障礙浮層，不需要「顯示在其他應用上層」權限。

## 建置

需要 JDK 17 與 Android SDK（compileSdk 35）。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew test
./gradlew assembleRelease
```

正式簽名請用專屬 keystore 的環境變數（不要把密碼寫進倉庫）：

```
KEYSTORE_FILE
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD
```

## License

MIT

目前發行版本：0.1.3。
