# 填碼（auto-fill）

原生 Android 小工具：在網頁或 App 的驗證碼欄位被選取時，暫時監看通知。驗證碼一來就半屏詢問是否填入；你點「填入」才會寫入欄位，不會寫入剪貼簿，也不會讀取簡訊收件匣。

顯示名稱：填碼  
applicationId：`com.wusper.auto.fill`  
介面預設繁體中文，另有简体中文與 English。Material 3 / Material You 動態色彩。目前發行版本：0.1.1。

## 行為

- 預設不會 24 小時監聽通知。只有在驗證碼欄位聚焦、短暫失焦後的約 80 秒寬限，或你在主畫面手動「開始監看」時才會解析通知。
- 欄位判斷：`autofillHints`（smsOTP / emailOTP 等）、數字輸入、提示文字含「驗證碼／验证码／OTP／verification code」，或 4–8 位長度。
- 離開驗證碼頁（換成其他 App）、逾時或關掉主開關，就停止監看。
- 確認畫面只顯示抽出的驗證碼與簡短來源（App 名稱或短標題），不顯示完整簡訊或郵件本文。
- 填入失敗時不做其他動作，你可以手動輸入。
- 不連網、無分析、不讀 SMS Provider。

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

目前發行版本：0.1.1。
