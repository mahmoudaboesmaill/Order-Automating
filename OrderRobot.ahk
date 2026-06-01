#Requires AutoHotkey v2.0
#SingleInstance Force

; ================================================================================
; روبوت محمود الموحد (النسخة المصلحة والمؤمنة 100%)
; التشغيل: Ctrl + Shift + S
; الإيقاف الطارئ: Ctrl + Shift + Q
; الاستكمال: Ctrl + Shift + R
; ================================================================================

SetTitleMatchMode(2)
global IsPausedForError := false
jsonFile := EnvGet("TEMP") . "\final_invoice.json"

^+r:: {
    global IsPausedForError := false
    ToolTip("✅ جاري الاستكمال...")
    SetTimer(() => ToolTip(), -2000)
}

^+q:: {
    ToolTip("🛑 تم إيقاف الروبوت")
    Sleep(1000)
    Reload()
}

ClearField() {
    Send("{Home}")
    Sleep(150)
    Send("+{End}")
    Sleep(150)
    Send("{Backspace}")
    Sleep(150)
}

^+s:: {
    if !FileExist(jsonFile) {
        MsgBox("⚠️ لم يتم العثور على ملف البيانات!")
        return
    }

    loop 3 {
        ToolTip("⚠️ ارفع يدك واضغط (كليك) في أول خانة... " . (4 - A_Index))
        Sleep(1000)
    }

    TargetWinID := WinGetID("A")
    WinActivate(TargetWinID)
    ToolTip("🚀 بدأ الإدخال الذكي...")

    RawJson := FileRead(jsonFile)

    ; استخراج رأس الفاتورة
    RegExMatch(RawJson, '"supplier_code":\s*"([^"]+)"', &supplierCode)
    RegExMatch(RawJson, '"invoice_number":\s*"([^"]+)"', &invoiceNum)

    ; المرحلة 1: الرأس
    ClearField()
    SendText("1")
    Send("{Enter}")
    Sleep(1000)

    ClearField()
    if IsSet(supplierCode)
        SendText(supplierCode[1])
    Send("{Enter}")
    Sleep(6000)

    ClearField()
    if IsSet(invoiceNum)
        SendText(invoiceNum[1])
    Send("{Enter}")
    Sleep(2000)
    Send("{Insert}")
    Sleep(1000)

    ; المرحلة 2: الأصناف
    Pos := 1
    While (Pos := RegExMatch(RawJson, '\{[^{}]*\}', &ItemBlock, Pos + 1)) {
        if !InStr(ItemBlock[0], '"itm_code"') {
            continue
        }

        ; استخراج البيانات مع تأمين البحث (RegEx)
        RegExMatch(ItemBlock[0], '"itm_code":\s*"([^"]+)"', &itmCode)
        RegExMatch(ItemBlock[0], '"quantity":\s*(\d+)', &qty)
        RegExMatch(ItemBlock[0], '"bonus":\s*(\d+)', &bonus)
        RegExMatch(ItemBlock[0], '"taxes":\s*([\d\.]+)', &taxValue)

        ; فحص سعر البيع (saleP) مع دعم الرقم السالب
        foundSale := RegExMatch(ItemBlock[0], '"sale_price":\s*([-]?[\d\.]+)', &saleP)

        ; فحص سعر الشراء (purchaseP)
        foundPurchase := RegExMatch(ItemBlock[0], '"price":\s*([\d\.]+)', &purchaseP)

        ; أ. كود الصنف
        if IsSet(itmCode) {
            SendText(itmCode[1])
            Send("{Enter}")
        }

        Sleep(1000)
        if WinExist("خطأ") {
            global IsPausedForError := true
            SoundBeep(750, 1000)
            while IsPausedForError {
                ToolTip("⚠️ خطأ في الكود! صلح الخطأ ثم اضغط Ctrl+Shift+R")
                Sleep(200)
            }
            ToolTip()
        }
        Sleep(1200)

        ; ب. كمية ثم بونص
        if IsSet(qty)
            SendText(qty[1])
        Send("{Enter}{Enter}")
        Sleep(1000)

        if IsSet(bonus)
            SendText(bonus[1])
        Send("{Enter}")
        Sleep(600)

        ; ج. صلاحية ورصيد
        Send("{Enter}{Enter}")
        Sleep(400)

        ; د. منطق سعر البيع (تخطي لو دريم)
        if (foundSale) {
            if (saleP[1] = "-1") {
                Send("{Enter}") ; تخطي للحفاظ على السعر القديم
            } else {
                SendText(saleP[1])
                Send("{Enter}")
            }
        } else {
            Send("{Enter}") ; لو السعر مش موجود تخطى
        }
        Sleep(600)

        ; هـ. الضريبة والتخطّي لسعر الشراء
        if IsSet(taxValue)
            SendText(taxValue[1])

        Loop 4 {
            Send("{Enter}")
            Sleep(500)
        }

        ; و. سعر الشراء
        if (foundPurchase) {
            SendText(purchaseP[1])
            Send("{Enter}")
        }
        Sleep(800)

        Send("{Insert}")
        Sleep(200)
    }
    ToolTip()
    MsgBox("✅ تمت المهمة بنجاح!")
}
