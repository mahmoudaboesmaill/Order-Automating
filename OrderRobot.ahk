#Requires AutoHotkey v2.0
#SingleInstance Off

; E-PLUS purchase-invoice robot.
; It enters data only. Saving the invoice is always a manual action.

SetTitleMatchMode(2)
; Use event mode with a deliberate key delay. E-PLUS is an older desktop
; application and can lose focus when several values arrive too quickly.
SendMode("Event")
SetKeyDelay(90, 50)
global IsPausedForError := false
global IsRunning := false
global PauseRequested := false
global IsManuallyPaused := false
global RuntimeDir := EnvGet("ORDER_ROBOT_RUNTIME_DIR")
if RuntimeDir = "" {
    RuntimeDir := EnvGet("LOCALAPPDATA") . "\OrderAutomating"
}
global HeaderFile := RuntimeDir . "\final_invoice_header.tsv"
global ItemsFile := RuntimeDir . "\final_invoice_items.tsv"
global PriceAlertsFile := RuntimeDir . "\price_alerts.txt"
global TraceFile := RuntimeDir . "\robot_trace.log"
global ControlFile := RuntimeDir . "\robot_control.ini"
global CheckpointFile := RuntimeDir . "\robot_checkpoint.state"
global SessionInitialized := false
global SessionJobID := ""
global SessionStatus := "idle"
global SessionPhase := "idle"
global SessionHeaderEntered := false
global SessionNextIndex := 0
global SessionCurrentIndex := -1
global SessionTotalItems := 0
global SessionWindowID := 0
global RobotMutexHandle := 0

OnExit(RobotSessionOnExit)

^+s::ProcessInvoice()

^+r:: {
    global IsPausedForError := false
    global SessionCurrentIndex, SessionNextIndex
    WriteSessionCheckpoint("running", "item_in_progress", SessionCurrentIndex, SessionNextIndex)
    ToolTip("✅ جاري الاستكمال...")
    SetTimer(() => ToolTip(), -1500)
}

; Safe manual pause/resume. A pause request is honoured only after the current
; item is complete and E-PLUS is ready at the next blank item-code cell.
^+p:: {
    global IsRunning, PauseRequested, IsManuallyPaused
    if !IsRunning {
        ToolTip("ℹ️ لا توجد فاتورة يعمل عليها الروبوت الآن")
        SetTimer(() => ToolTip(), -1800)
        return
    }

    if IsManuallyPaused {
        activeTitle := WinGetTitle("A")
        if !InStr(activeTitle, "e-Plus") || !InStr(activeTitle, "فاتورة شراء") {
            SoundBeep(650, 500)
            ToolTip("⚠️ ارجع إلى فاتورة الشراء واضغط داخل خانة كود الصنف التالي، ثم اضغط Ctrl+Shift+P")
            return
        }
        IsManuallyPaused := false
        ToolTip("▶️ جاري استكمال فاتورة الشراء...")
        SetTimer(() => ToolTip(), -1800)
        return
    }

    if PauseRequested {
        PauseRequested := false
        ToolTip("▶️ تم إلغاء طلب الإيقاف؛ الروبوت مستمر")
        SetTimer(() => ToolTip(), -1800)
        return
    }

    PauseRequested := true
    ToolTip("⏳ سيتم الإيقاف بأمان بعد إكمال الصنف الحالي...")
}

^+q:: {
    ToolTip("🛑 تم إيقاف الروبوت")
    Sleep(500)
    ExitApp()
}

ReadRobotControl() {
    global ControlFile
    if !FileExist(ControlFile) {
        throw Error("ملف التحكم في جلسة الإرسال غير موجود")
    }
    return Map(
        "job_id", Trim(IniRead(ControlFile, "robot", "job_id", "")),
        "mode", Trim(IniRead(ControlFile, "robot", "mode", "start")),
        "start_index", IniRead(ControlFile, "robot", "start_index", "0") + 0,
        "skip_header", IniRead(ControlFile, "robot", "skip_header", "0") = "1",
        "expected_window_id", IniRead(ControlFile, "robot", "expected_window_id", "0") + 0,
        "supplier_code", Trim(IniRead(ControlFile, "robot", "supplier_code", "")),
        "invoice_number", Trim(IniRead(ControlFile, "robot", "invoice_number", "")),
        "total_items", IniRead(ControlFile, "robot", "total_items", "0") + 0
    )
}

ReadControlCommand() {
    global ControlFile
    if !FileExist(ControlFile) {
        return "none"
    }
    return StrLower(Trim(IniRead(ControlFile, "robot", "command", "none")))
}

ReadCheckpointValue(key, defaultValue := "") {
    global CheckpointFile
    if !FileExist(CheckpointFile) {
        return defaultValue
    }
    try {
        for rawLine in StrSplit(FileRead(CheckpointFile, "UTF-8"), "`n") {
            parts := StrSplit(Trim(rawLine, "`r "), "=", , 2)
            if parts.Length = 2 && parts[1] = key {
                return parts[2]
            }
        }
    } catch {
        return defaultValue
    }
    return defaultValue
}

GetCurrentRobotPID() {
    return DllCall("GetCurrentProcessId", "uint")
}

EnsureNoOtherRobotProcess(jobID) {
    existingJobID := ReadCheckpointValue("job_id")
    existingStatus := ReadCheckpointValue("status")
    existingPID := ReadCheckpointValue("pid", "0") + 0
    currentPID := GetCurrentRobotPID()
    if (
        existingJobID = jobID
        && existingPID > 0
        && existingPID != currentPID
        && (existingStatus = "running" || existingStatus = "paused" || existingStatus = "launching")
        && ProcessExist(existingPID)
    ) {
        throw Error("يوجد روبوت آخر يعمل بالفعل على نفس جلسة الفاتورة")
    }
}

AcquireRobotMutex() {
    global RobotMutexHandle
    if RobotMutexHandle {
        return
    }
    handle := DllCall(
        "CreateMutexW",
        "ptr", 0,
        "int", false,
        "str", "Local\OrderAutomatingSendRobot",
        "ptr"
    )
    lastError := DllCall("GetLastError")
    if !handle {
        throw Error("تعذر إنشاء قفل روبوت الإدخال")
    }
    if lastError = 183 {
        DllCall("CloseHandle", "ptr", handle)
        throw Error("يوجد روبوت إدخال آخر يعمل بالفعل")
    }
    RobotMutexHandle := handle
}

ReleaseRobotMutex() {
    global RobotMutexHandle
    if !RobotMutexHandle {
        return
    }
    try DllCall("ReleaseMutex", "ptr", RobotMutexHandle)
    try DllCall("CloseHandle", "ptr", RobotMutexHandle)
    RobotMutexHandle := 0
}

AcknowledgeControlCommand() {
    global ControlFile
    try IniWrite("none", ControlFile, "robot", "command")
}

WriteSessionCheckpoint(status, phase, currentIndex, nextIndex) {
    global CheckpointFile, SessionInitialized, SessionJobID, SessionStatus, SessionPhase
    global SessionHeaderEntered, SessionNextIndex, SessionCurrentIndex
    global SessionTotalItems, SessionWindowID
    if !SessionInitialized {
        return
    }

    SessionStatus := status
    SessionPhase := phase
    SessionCurrentIndex := currentIndex
    SessionNextIndex := nextIndex
    currentPID := GetCurrentRobotPID()
    updatedEpoch := DateDiff(A_NowUTC, "19700101000000", "Seconds")
    content := "job_id=" . SessionJobID . "`n"
    content .= "status=" . status . "`n"
    content .= "phase=" . phase . "`n"
    content .= "header_entered=" . (SessionHeaderEntered ? 1 : 0) . "`n"
    content .= "next_index=" . nextIndex . "`n"
    content .= "current_index=" . currentIndex . "`n"
    content .= "total_items=" . SessionTotalItems . "`n"
    content .= "window_id=" . SessionWindowID . "`n"
    content .= "pid=" . currentPID . "`n"
    content .= "updated_epoch=" . updatedEpoch . "`n"

    temporaryFile := CheckpointFile . "." . currentPID . ".tmp"
    try {
        try FileDelete(temporaryFile)
        FileAppend(content, temporaryFile, "UTF-8-RAW")
        FileMove(temporaryFile, CheckpointFile, 1)
    } catch as error {
        WriteTrace("checkpoint_write_failed: " . error.Message)
    }
}

RobotSessionOnExit(exitReason, exitCode) {
    global SessionInitialized, SessionStatus, SessionPhase
    global SessionCurrentIndex, SessionNextIndex
    if SessionInitialized
        && SessionStatus != "completed"
        && SessionStatus != "cancelled"
        && SessionStatus != "interrupted" {
        WriteSessionCheckpoint("interrupted", SessionPhase, SessionCurrentIndex, SessionNextIndex)
    }
    ReleaseRobotMutex()
}

CancelSession(phase := "cancelled") {
    global SessionCurrentIndex, SessionNextIndex
    AcknowledgeControlCommand()
    WriteSessionCheckpoint("cancelled", phase, SessionCurrentIndex, SessionNextIndex)
    throw Error("__SESSION_CANCELLED__")
}

ClearField() {
    Send("{Home}")
    Sleep(120)
    Send("+{End}")
    Sleep(120)
    Send("{Backspace}")
    Sleep(120)
}

TypeFieldValue(value, fieldLabel, itmCode := "") {
    ; E-PLUS selects the active grid-cell value when focus arrives, so typing
    ; replaces it directly. Do not clear numeric grid cells first: an empty
    ; numeric cell is immediately normalized to 0 and typing 20 can become 200.
    ; Type into E-PLUS instead of pasting. Clipboard paste was occasionally
    ; dropped while a freshly inserted row was still initializing, causing the
    ; following quantity to be interpreted as an item code.
    ;
    ; Pre-type settling: give the legacy grid time to finish any internal
    ; transition (focus change, row init, duplicate-code lookup) before the
    ; first character arrives. Without this gap a momentary CPU/disk spike can
    ; cause characters to land in the wrong cell.
    Sleep(220)
    SendText(value)
    ; Post-type settling: let the grid register the value before the caller
    ; commits it with Enter.
    Sleep(550)
    WriteTrace("item=" . itmCode . " field=" . fieldLabel . " typed=" . value)
}

CopyActiveCellText(timeoutSeconds := 0.25) {
    savedClip := A_Clipboard
    A_Clipboard := ""
    Send("^c")
    copied := ClipWait(timeoutSeconds, 1)
    actual := copied ? Trim(A_Clipboard, " `t`r`n") : ""
    A_Clipboard := savedClip
    return actual
}

VerifyAndTypeQty(qty, itmCode) {
    ; Write quantity exactly once, then compare it numerically. E-PLUS copies
    ; integer quantities with a trailing dot (for example 19.), so a textual
    ; comparison reports a false mismatch. Never clear/retype this numeric cell:
    ; E-PLUS restores 0 after clearing and a retry can turn 19 into 190.
    TypeFieldValue(qty, "الكمية", itmCode)

    ; Ctrl+A is an E-PLUS search shortcut. Ctrl+C alone copies the active cell.
    actualRaw := CopyActiveCellText(1.0)

    actual := RegExReplace(actualRaw, "\.$", "")
    expected := RegExReplace(Trim(qty), "\.$", "")
    WriteTrace("item=" . itmCode . " qty_verify: expected=" . expected . " actual_raw=" . actualRaw . " normalized=" . actual)

    if actual = "" {
        WriteTrace("item=" . itmCode . " qty_verify unavailable — proceeding without retry")
        return
    }
    if IsNumber(actual) && IsNumber(expected) && Abs((actual + 0) - (expected + 0)) < 0.0001 {
        return
    }

    ; Keep the value untouched and let the pharmacist correct only this cell.
    PauseForManualFix("⚠️ الكمية المقروءة لا تطابق الفاتورة للصنف: " . itmCode
        . "`nالمتوقع: " . expected . " | الموجود: " . actualRaw
        . "`nصحح الخانة إن لزم ثم اضغط Ctrl+Shift+R")
}

WriteTrace(message) {
    global TraceFile
    try FileAppend(FormatTime(A_Now, "yyyy-MM-dd HH:mm:ss") . "`t" . message . "`n", TraceFile, "UTF-8")
}


PauseAtSafeItemBoundary(targetWinID) {
    global PauseRequested, IsManuallyPaused
    global SessionCurrentIndex, SessionNextIndex
    command := ReadControlCommand()
    if command = "cancel" {
        CancelSession("cancelled_at_safe_boundary")
    }
    if !PauseRequested {
        return
    }

    PauseRequested := false
    IsManuallyPaused := true
    WriteSessionCheckpoint("paused", "safe_boundary", -1, SessionNextIndex)
    WriteTrace("manual pause at next item-code cell")
    SoundBeep(850, 700)
    while IsManuallyPaused {
        command := ReadControlCommand()
        if command = "cancel" {
            CancelSession("cancelled_at_safe_boundary")
        }
        if command = "resume" {
            AcknowledgeControlCommand()
            IsManuallyPaused := false
            continue
        }
        ToolTip(
            "⏸️ الروبوت متوقف بأمان عند الصنف التالي"
            . "`nاترك فاتورة الشراء مفتوحة واعمل فاتورة البيع"
            . "`nللعودة: افتح فاتورة الشراء، اضغط خانة كود الصنف التالي، ثم Ctrl+Shift+P"
        )
        Sleep(250)
    }
    ToolTip()
    if !WinExist("ahk_id " . targetWinID) {
        throw Error("تم إغلاق نافذة فاتورة الشراء أثناء الإيقاف المؤقت")
    }
    WinActivate("ahk_id " . targetWinID)
    if !WinWaitActive("ahk_id " . targetWinID, , 3) {
        throw Error("لم يتم تأكيد الرجوع إلى نافذة فاتورة الشراء")
    }
    Sleep(350)
    WriteSessionCheckpoint("running", "safe_boundary", -1, SessionNextIndex)
    WriteTrace("manual resume from next item-code cell")
}

PauseForManualFix(message) {
    global IsPausedForError, SessionCurrentIndex, SessionNextIndex
    IsPausedForError := true
    WriteSessionCheckpoint("paused", "item_needs_manual_fix", SessionCurrentIndex, SessionNextIndex)
    SoundBeep(750, 900)
    while IsPausedForError {
        if ReadControlCommand() = "cancel" {
            CancelSession("cancelled_mid_item")
        }
        ToolTip(message . "`nأكمل التصحيح ثم اضغط Ctrl+Shift+R")
        Sleep(200)
    }
    ToolTip()
    WriteSessionCheckpoint("running", "item_in_progress", SessionCurrentIndex, SessionNextIndex)
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
    global IsPausedForError, IsRunning, PauseRequested, IsManuallyPaused
    global HeaderFile, ItemsFile, PriceAlertsFile, TraceFile
    global SessionInitialized, SessionJobID, SessionStatus, SessionPhase
    global SessionHeaderEntered, SessionNextIndex, SessionCurrentIndex
    global SessionTotalItems, SessionWindowID

    if IsRunning {
        return
    }
    if !FileExist(HeaderFile) || !FileExist(ItemsFile) {
        MsgBox("⚠️ لم يتم العثور على بيانات الفاتورة. أعد إرسالها من التطبيق.", "روبوت E-PLUS", "Icon!")
        return
    }

    try {
        control := ReadRobotControl()
        header := ReadHeader()
        itemRows := []
        lines := StrSplit(FileRead(ItemsFile), "`n")
        for rawLine in lines {
            line := Trim(rawLine, "`r`t ")
            if line = "" {
                continue
            }
            fields := StrSplit(line, "`t")
            if fields.Length >= 7 && fields[1] != "" {
                itemRows.Push(fields)
            }
        }
        if control["job_id"] = "" {
            throw Error("معرّف جلسة الإرسال غير صالح")
        }
        EnsureNoOtherRobotProcess(control["job_id"])
        if control["total_items"] != itemRows.Length {
            throw Error("عدد الأصناف لا يطابق جلسة الإرسال المحفوظة")
        }
        if control["start_index"] < 0 || control["start_index"] > itemRows.Length {
            throw Error("موضع الاستكمال خارج نطاق أصناف الفاتورة")
        }
        if header[1] != control["supplier_code"] || header[2] != control["invoice_number"] {
            throw Error("بيانات رأس الفاتورة لا تطابق جلسة الإرسال")
        }
        AcquireRobotMutex()
    } catch as error {
        MsgBox("⚠️ " . error.Message, "روبوت E-PLUS", "Icon!")
        return
    }

    SessionInitialized := true
    SessionJobID := control["job_id"]
    SessionTotalItems := itemRows.Length
    SessionHeaderEntered := control["skip_header"]
    SessionNextIndex := control["start_index"]
    SessionCurrentIndex := -1
    SessionWindowID := control["expected_window_id"]
    IsRunning := true
    PauseRequested := false
    IsManuallyPaused := false
    try {
        mode := control["mode"]
        startIndex := control["start_index"]
        skipHeader := control["skip_header"]
        if mode != "resume" {
            try FileDelete(TraceFile)
        }
        WriteTrace("invoice session=" . SessionJobID . " mode=" . mode . " start_index=" . startIndex)
        WriteSessionCheckpoint("running", mode . "_preparing", -1, startIndex)
        if ReadControlCommand() = "cancel" {
            CancelSession("cancelled_before_start")
        }

        supplierCode := header[1]
        invoiceNum := header[2]

        if skipHeader {
            targetWinID := control["expected_window_id"]
            if targetWinID <= 0 || !WinExist("ahk_id " . targetWinID) {
                WriteSessionCheckpoint("interrupted", "resume_window_missing", -1, startIndex)
                throw Error("نافذة فاتورة E-PLUS الجزئية لم تعد موجودة؛ تم منع الكتابة في نافذة أخرى")
            }
            targetTitle := WinGetTitle("ahk_id " . targetWinID)
            if !InStr(targetTitle, "e-Plus") {
                WriteSessionCheckpoint("interrupted", "resume_window_missing", -1, startIndex)
                throw Error("النافذة المحفوظة ليست نافذة E-PLUS المطلوبة")
            }
            WinActivate("ahk_id " . targetWinID)
            if !WinWaitActive("ahk_id " . targetWinID, , 3) {
                WriteSessionCheckpoint("interrupted", "resume_window_missing", -1, startIndex)
                throw Error("تعذر تنشيط نفس نافذة فاتورة E-PLUS")
            }
            answer := MsgBox(
                "سيتم استكمال الفاتورة " . invoiceNum . " للمورد " . supplierCode
                . " من الصنف رقم " . (startIndex + 1) . ".`n`n"
                . "تأكد أن هذه هي نفس الفاتورة الجزئية، ثم اضغط موافق.",
                "استكمال آمن | E-PLUS",
                "OKCancel Icon!"
            )
            if answer = "Cancel" {
                WriteSessionCheckpoint("interrupted", "resume_user_cancelled", -1, startIndex)
                return
            }
            WinActivate("ahk_id " . targetWinID)
            Loop 5 {
                ToolTip(
                    "⚠️ اضغط داخل خانة كود الصنف الفارغة التالية في نفس الفاتورة... "
                    . (6 - A_Index)
                )
                Sleep(1000)
            }
            if WinGetID("A") != targetWinID {
                WriteSessionCheckpoint("interrupted", "resume_window_missing", -1, startIndex)
                throw Error("لم يتم تأكيد نفس نافذة E-PLUS؛ لم تُكتب أي بيانات")
            }
            ToolTip("▶️ جاري استكمال الفاتورة من الصنف " . (startIndex + 1))
            WriteSessionCheckpoint("running", "safe_boundary", -1, startIndex)
        } else {
            ; The user explicitly chooses the first E-PLUS field. This prevents data
            ; from ever being typed into an unrelated application.
            Loop 3 {
                ToolTip("⚠️ اضغط داخل خانة (المخزن) في رأس فاتورة الشراء، وليس داخل جدول الأصناف... " . (4 - A_Index))
                Sleep(1000)
            }

            targetWinID := WinGetID("A")
            targetTitle := WinGetTitle("ahk_id " . targetWinID)
            if !InStr(targetTitle, "e-Plus") {
                WriteSessionCheckpoint("interrupted", "target_window_rejected", -1, 0)
                MsgBox("⚠️ النافذة النشطة ليست E-PLUS. لم يتم إدخال أي بيانات.", "روبوت E-PLUS", "Icon!")
                return
            }

            SessionWindowID := targetWinID
            WinActivate("ahk_id " . targetWinID)
            if !WinWaitActive("ahk_id " . targetWinID, , 3) {
                WriteSessionCheckpoint("interrupted", "target_window_rejected", -1, 0)
                throw Error("لم يتم تأكيد تنشيط نافذة E-PLUS")
            }
            ToolTip("🚀 جاري إدخال فاتورة الشراء...")
            WriteSessionCheckpoint("running", "header_in_progress", -1, 0)

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
            ; The user's real E-PLUS workflow creates the first row directly with
            ; Insert; pressing Enter here moves focus away from the expected grid.
            Sleep(500)
            Send("{Insert}")
            Sleep(2000)
            SessionHeaderEntered := true
            WriteSessionCheckpoint("running", "safe_boundary", -1, 0)
            ; A pause requested while entering the invoice header is safe here,
            ; before the first item code is typed.
            PauseAtSafeItemBoundary(targetWinID)
        }

        itemCount := startIndex
        for rowNumber, fields in itemRows {
            itemIndex := rowNumber - 1
            if itemIndex < startIndex {
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

            WriteSessionCheckpoint("running", "item_in_progress", itemIndex, itemIndex)
            ToolTip("جارٍ إدخال الصنف " . (itemIndex + 1) . " من " . SessionTotalItems . ": " . itmCode)
            if (expiryMonth != "" || expiryYear != "") && (StrLen(expiryMonth) != 2 || StrLen(expiryYear) != 2 || !RegExMatch(expiryMonth, "^\d{2}$") || !RegExMatch(expiryYear, "^\d{2}$")) {
                PauseForManualFix("⚠️ تاريخ صلاحية غير صالح للصنف: " . itmCode)
            }
            if !WinExist("ahk_id " . targetWinID) {
                MsgBox("⚠️ تم إغلاق نافذة E-PLUS. لم يتم حفظ الفاتورة.", "روبوت E-PLUS", "Icon!")
                return
            }
            WinActivate("ahk_id " . targetWinID)
            if !WinWaitActive("ahk_id " . targetWinID, , 3) {
                throw Error("لم يتم تأكيد تنشيط نافذة E-PLUS قبل الصنف: " . itmCode)
            }

            ; الكود المحلي في قاعدة التطبيق هو كود E-PLUS؛ لا نحتاج الباركود
            ; الدولي لكي نعثر على الصنف.
            ; Submitting the code triggers an E-PLUS lookup, so it is validated
            ; by the lookup result/item name rather than by copying the cell
            ; while the lookup is running.
            TypeFieldValue(itmCode, "كود الصنف", itmCode)
            Send("{Enter}")
            ; The quantity cell becomes active quickly, but its untouched
            ; default value is not exposed to Ctrl+C. A short settle is more
            ; reliable than clipboard-based readiness detection and replaces
            ; the former fixed 4 s + 1.5 s wait.
            Sleep(650)
            if WinExist("خطأ") || WinExist("بحث عن صنف") || WinExist("تحذير") {
                PauseForManualFix("⚠️ تحقق من الصنف: " . itmCode)
            }

            ; Exact manual workflow supplied by the user: code Enter -> quantity.
            ; Quantity is written once and compared numerically after removing
            ; E-PLUS's trailing dot. A mismatch pauses without clearing/retrying.
            VerifyAndTypeQty(qty, itmCode)
            Send("{Enter}")
            ; Increased from 700 ms: E-PLUS must advance the active cell from
            ; quantity to the expiry field before the next character arrives.
            Sleep(1200)
            if expiryMonth != "" && expiryYear != "" {
                ; E-PLUS accepts the confirmed month/year as one four-digit MMYY field.
                TypeFieldValue(expiryMonth . expiryYear, "الصلاحية", itmCode)
            }
            Send("{Enter}") ; اعتماد الصلاحية أو تخطيها إن تركها المستخدم فارغة.
            ; Increased from 1400 ms to give E-PLUS time to move past the expiry
            ; cell and settle on the bonus field before typing starts.
            Sleep(1800)

            ; Zero bonus is skipped exactly as in the manual workflow. A
            ; non-zero bonus is typed before committing the field.
            if (bonus + 0) != 0 {
                TypeFieldValue(bonus, "البونص", itmCode)
            }
            ; This single Enter both commits/skips bonus and reaches sale price.
            ; It is the same Enter described by the manual workflow, not an
            ; additional transition after committing bonus.
            Send("{Enter}")
            ; Increased from 1200 ms: the sale-price field follows an internal
            ; recalculation step in E-PLUS that can be slow on large invoices.
            Sleep(1500)

            if updateSalePrice = "1" && salePrice != "0" {
                TypeFieldValue(salePrice, "سعر البيع", itmCode)
            }
            ; Commit or skip sale price, moving to the tax section.
            Send("{Enter}")
            Sleep(1500)

            ; The Enter immediately above (after sale price) is the first of
            ; the five transitions used when tax is zero. Four more reach the
            ; purchase-price field. With non-zero tax, type it at the reached
            ; tax cell and likewise use four Enters to reach purchase price.
            taxTransitions := 4
            if (taxValue + 0) > 0 {
                TypeFieldValue(taxValue, "الضريبة", itmCode)
            }
            Loop taxTransitions {
                Send("{Enter}")
                ; Increased from 650 ms: these four rapid Enters are the second
                ; most common source of cell-transition misalignment. Each Enter
                ; now waits long enough for E-PLUS to finish its internal update.
                Sleep(950)
            }

            TypeFieldValue(purchasePrice, "سعر الشراء", itmCode)
            Send("{Enter}")
            ; Increased from 1800 ms: allow the row total to be recalculated
            ; before Insert creates the next row.
            Sleep(2200)
            Send("{Insert}")
            ; Increased from 2000 ms: a repeated item code triggers a balance
            ; refresh in E-PLUS which delays the new row's readiness. 3200 ms
            ; was chosen to cover the observed worst case on the target machine.
            Sleep(3200)
            itemCount += 1
            WriteSessionCheckpoint("running", "item_completed", -1, itemCount)
            ; Manual interruptions are honoured only here so an item can never
            ; be left half-entered. The active cell is the next item code.
            PauseAtSafeItemBoundary(targetWinID)
        }


        ToolTip()
        WriteSessionCheckpoint("completed", "invoice_ready", -1, SessionTotalItems)
        ; Python writes this report as UTF-8 without a Windows ANSI code page.
        ; Specify the encoding so Arabic text is not displayed as mojibake.
        report := FileExist(PriceAlertsFile) ? FileRead(PriceAlertsFile, "UTF-8") : "لا يوجد تقرير تغيّرات أسعار."
        summary := "✅ تم إدخال " . itemCount . " صنفاً في E-PLUS.`n`n"
        summary .= "الفاتورة لم تُحفظ. راجع الأصناف والإجمالي ثم اضغط حفظ بنفسك.`n`n"
        summary .= report
        MsgBox(summary, "E-PLUS | مراجعة قبل الحفظ", "Iconi")
    } catch as error {
        ToolTip()
        if error.Message = "__SESSION_CANCELLED__" {
            MsgBox("🛑 تم إلغاء جلسة الإرسال. لم يتم حفظ الفاتورة تلقائياً.", "روبوت E-PLUS", "Icon!")
        } else {
            if SessionStatus != "interrupted" {
                errorPhase := SessionCurrentIndex >= 0 ? "item_error" : SessionPhase
                WriteSessionCheckpoint("interrupted", errorPhase, SessionCurrentIndex, SessionNextIndex)
            }
            MsgBox("❌ توقف الروبوت: " . error.Message . "`nلم يتم تنفيذ حفظ تلقائي.", "روبوت E-PLUS", "Iconx")
        }
    } finally {
        PauseRequested := false
        IsManuallyPaused := false
        IsRunning := false
        ReleaseRobotMutex()
    }
}

; server.py passes --run. Ctrl+Shift+S remains available for manual retries.
RunInvoiceAndExit(*) {
    ProcessInvoice()
    ExitApp()
}

if A_Args.Length && A_Args[1] = "--run" {
    SetTimer(RunInvoiceAndExit, -250)
}
