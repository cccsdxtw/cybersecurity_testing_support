# OverlayTester

`OverlayTester` 是一個 Flutter 的全螢幕覆蓋工具，用於測試、訊息提示或暫時遮罩整個頁面，方便快速調試 UI、顯示 Loading、Toast 或暫時的 Overlay 元件。

---

## 功能特色

- **全螢幕覆蓋**：可快速覆蓋整個頁面內容。
- **可自訂 Widget**：覆蓋的內容可以是任何 Widget，例如 Loading、提示文字、測試訊息。
- **易於使用**：透過單一方法呼叫即可插入 Overlay。
- **自動管理**：可以手動或程式控制顯示與移除。

---

## 安裝

將 `overlay_tester.dart` 放入你的 Flutter 專案中，然後引入：

`dart
import 'overlay_tester.dart'; `

## 顯示 Overlay

```dart
OverlayTester.show(
context: context,
child: Container(
color: Colors.black.withOpacity(0.5),
child: const Center(
child: CircularProgressIndicator(),
),
),
);
```

## 移除 Overlay

`OverlayTester.hide();`

## 參數說明

| 參數名        | 類型             | 預設值     | 說明                     |
| ---------- | -------------- | ------- | ---------------------- |
| `context`  | `BuildContext` | 必填      | 用於取得 OverlayState      |
| `child`    | `Widget`       | 必填      | 要顯示在 Overlay 上的 Widget |
| `duration` | `Duration?`    | `null`  | 可選，設定自動隱藏的時間           |
| `opaque`   | `bool`         | `false` | 是否完全遮罩背景（true 會阻擋點擊事件） |

## 範例

// 顯示半透明遮罩 + Loading
`OverlayTester.show(
  context: context,
  child: Container(
    color: Colors.black.withOpacity(0.5),
    child: const Center(
      child: CircularProgressIndicator(),
    ),
  ),
);`

// 3 秒後自動移除
`Future.delayed(const Duration(seconds: 3), () {
  OverlayTester.hide();
});`

## 注意事項

・ Overlay 顯示時，會疊加在當前頁面之上，請注意避免遮擋重要互動元素。
・ 請確認每次呼叫 show 時對應呼叫 hide，避免多層 Overlay 疊加。
・ 適合用於快速測試 UI 元件或 Loading 效果，但非正式 Alert/Dialog 替代方案。

## 授權

MIT License
