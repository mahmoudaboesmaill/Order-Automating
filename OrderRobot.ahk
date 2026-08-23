#Requires AutoHotkey v2.0
#SingleInstance Force

; E-PLUS purchase-invoice robot.
; It enters data only. Saving the invoice is always a manual action.

SetTitleMatchMode(2)
; Use event mode with a deliberate key delay. E-PLUS is an older desktop
; application and can lose focus when several values arrive too quickly.
SendMode("Event")
SetKeyDelay(90, 50)
global IsPausedForError := false
global IsRunning := false
global RuntimeDir := EnvGet("ORDER_ROBOT_RUNTIME_DIR")
if RuntimeDir = "" {
    RuntimeDir := EnvGet("LOCALAPPDATA") . "\OrderAutomating"
}
global HeaderFile := RuntimeDir . "\final_invoice_header.tsv"
global ItemsFile := RuntimeDir . "\final_invoice_items.tsv"
global PriceAlertsFile := RuntimeDir . "\price_alerts.txt"

^+s::ProcessInvoice()

^+r:: {
    global IsPausedForError := false
    ToolTip("✅ جاري الاستكمال...")
    SetTimer(() => ToolTip(), -1500)
}

^+q:: {
    ToolTip("🛑 تم إيقاف الروبوت")
    Sleep(500)
    ExitApp()
}

ClearField() {
    Send("{Home}")
    Sleep(120)
    Send("+{End}")
    Sleep(120)
    Send("{Backspace}")
    Sleep(120)
}

PauseForManualFix(message) {
    global IsPausedForError
    IsPausedForError := true
    SoundBeep(750, 900)
    while IsPausedForError {
        ToolTip(message . "`nأكمل التصحيح ثم اضغط Ctrl+Shift+R")
        Sleep(200)
    }
    ToolTip()
}

ReadHeader() {
    global HeaderFile
    header := StrSplit(Trim(FileRead(HeaderFile), "`r`n"), "`t")
    if header.Length < 2 || header[1] = "" || header[2] = "" {
        throw Error("بيانات رأس الفاتورة غير صالحة")
    }
    return header
}

ProcessInvoice(*) {
    global IsPausedForError, IsRunning, HeaderFile, ItemsFile, PriceAlertsFile

    if IsRunning {
        return
    }
    if !FileExist(HeaderFile) || !FileExist(ItemsFile) {
        MsgBox("⚠️ لم يتم العثور على بيانات الفاتورة. أعد إرسالها من التطبيق.", "روبوت E-PLUS", "Icon!")
        return
    }

    try {
        header := ReadHeader()
    } catch as error {
        MsgBox("⚠️ " . error.Message, "روبوت E-PLUS", "Icon!")
        return
    }

    IsRunning := true
    try {
        ; The user explicitly chooses the first E-PLUS field. This prevents data
        ; from ever being typed into an unrelated application.
        Loop 3 {
            ToolTip("⚠️ اضغط داخل خانة (المخزن) في رأس فاتورة الشراء، وليس داخل جدول الأصناف... " . (4 - A_Index))
            Sleep(1000)
        }

        targetWinID := WinGetID("A")
        targetTitle := WinGetTitle("ahk_id " . targetWinID)
        if !InStr(targetTitle, "e-Plus") {
            MsgBox("⚠️ النافذة النشطة ليست E-PLUS. لم يتم إدخال أي بيانات.", "روبوت E-PLUS", "Icon!")
            return
        }

        WinActivate("ahk_id " . targetWinID)
        ToolTip("🚀 جاري إدخال فاتورة الشراء...")

        supplierCode := header[1]
        invoiceNum := header[2]

        ; رأس الفاتورة: مخزن 1 ثم المورد ثم رقم الفاتورة.
        ClearField()
        SendText("1")
        Send("{Enter}")
        Sleep(800)

        ClearField()
        SendText(supplierCode)
        Send("{Enter}")
        ; E-PLUS may freeze briefly while loading the supplier balance/name.
        ; Do not type the invoice number until that lookup has completed.
        Sleep(12000)

        ClearField()
        SendText(invoiceNum)
        Send("{Enter}")
        Sleep(1800)
        Send("{Insert}")
        Sleep(1200)

        itemCount := 0
        lines := StrSplit(FileRead(ItemsFile), "`n")
        for rawLine in lines {
            line := Trim(rawLine, "`r`t ")
            if line = "" {
                continue
            }
            fields := StrSplit(line, "`t")
            if fields.Length < 7 {
                continue
            }

            itmCode := fields[1]
            qty := fields[2]
            bonus := fields[3]
            taxValue := fields[4]
            purchasePrice := fields[5]
            salePrice := fields[6]
            updateSalePrice := fields[7]
            expiryMonth := fields.Length >= 8 ? fields[8] : ""
            expiryYear := fields.Length >= 9 ? fields[9] : ""

            if itmCode = "" {
                continue
            }
            if (expiryMonth != "" || expiryYear != "") && (StrLen(expiryMonth) != 2 || StrLen(expiryYear) != 2 || !RegExMatch(expiryMonth, "^\d{2}$") || !RegExMatch(expiryYear, "^\d{2}$")) {
                PauseForManualFix("⚠️ تاريخ صلاحية غير صالح للصنف: " . itmCode)
            }
            if !WinExist("ahk_id " . targetWinID) {
                MsgBox("⚠️ تم إغلاق نافذة E-PLUS. لم يتم حفظ الفاتورة.", "روبوت E-PLUS", "Icon!")
                return
            }
            WinActivate("ahk_id " . targetWinID)

            ; الكود المحلي في قاعدة التطبيق هو كود E-PLUS؛ لا نحتاج الباركود
            ; الدولي لكي نعثر على الصنف.
            SendText(itmCode)
            Send("{Enter}")
            Sleep(2200)

            if WinExist("خطأ") || WinExist("بحث عن صنف") {
                PauseForManualFix("⚠️ تحقق من الصنف: " . itmCode)
            }
            Sleep(1500)

            SendText(qty)
            Send("{Enter}")
            Sleep(700)
            if expiryMonth != "" && expiryYear != "" {
                ; E-PLUS accepts the confirmed month/year as one four-digit MMYY field.
                SendText(expiryMonth . expiryYear)
            }
            Send("{Enter}") ; اعتماد الصلاحية أو تخطيها إن تركها المستخدم فارغة.
            Sleep(1400)

            SendText(bonus)
            Send("{Enter}{Enter}{Enter}") ; تخطي الرصيد والوصول لسعر البيع.
            Sleep(1200)

            if updateSalePrice = "1" && salePrice != "0" {
                SendText(salePrice)
            }
            ; في دريم يبقى سعر البيع الحالي كما هو.
            Send("{Enter}")
            Sleep(1200)

            ; ضريبة الصنف ثم الانتقال إلى سعر الشراء. التاريخ لا يُستخرج من
            ; الورقة؛ يُكتب هنا فقط إذا أكده المستخدم من شاشة المراجعة.
            SendText(taxValue)
            ; After the tax field, three transitions land on س.شراء. Four
            ; transitions skip it and place the value in س.عملة instead.
            Loop 3 {
                Send("{Enter}")
                Sleep(650)
            }

            SendText(purchasePrice)
            Send("{Enter}")
            Sleep(1800)
            Send("{Insert}")
            Sleep(800)
            itemCount += 1
        }

        ToolTip()
        report := FileExist(PriceAlertsFile) ? FileRead(PriceAlertsFile) : "لا يوجد تقرير تغيّرات أسعار."
        summary := "✅ تم إدخال " . itemCount . " صنفاً في E-PLUS.`n`n"
        summary .= "الفاتورة لم تُحفظ. راجع الأصناف والإجمالي ثم اضغط حفظ بنفسك.`n`n"
        summary .= report
        MsgBox(summary, "E-PLUS | مراجعة قبل الحفظ", "Iconi")
    } catch as error {
        ToolTip()
        MsgBox("❌ توقف الروبوت: " . error.Message . "`nلم يتم تنفيذ حفظ تلقائي.", "روبوت E-PLUS", "Iconx")
    } finally {
        IsRunning := false
    }
}

; server.py passes --run. Ctrl+Shift+S remains available for manual retries.
if A_Args.Length && A_Args[1] = "--run" {
    SetTimer(ProcessInvoice, -250)
}
