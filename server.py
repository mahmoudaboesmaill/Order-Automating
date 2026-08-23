"""Local bridge between the Android reviewer, Gemini, and E-PLUS automation.

All invoice artefacts are written atomically into the Windows temp directory so
AutoHotkey never reads a half-written invoice. The robot deliberately stops
before saving the E-PLUS invoice; saving remains a human action.
"""

from __future__ import annotations

import base64
from datetime import date
import hmac
import json
import math
import os
import re
import socket
from threading import Lock
import unicodedata
from io import BytesIO
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

try:
    import google.generativeai as genai
except ImportError:  # Keep the local E-PLUS bridge usable for /invoice checks.
    genai = None
try:
    from pypdf import PdfReader, PdfWriter, Transformation
    from pypdf.generic import RectangleObject
except ImportError:  # The OCR endpoint still starts; PDF inspection reports a clear error.
    PdfReader = PdfWriter = Transformation = RectangleObject = None
try:
    from PIL import Image, ImageEnhance, ImageFilter
except ImportError:
    Image = ImageEnhance = ImageFilter = None
from flask import Flask, jsonify, request


PROJECT_DIR = Path(__file__).resolve().parent
RUNTIME_DIR = Path(
    os.environ.get(
        "ORDER_ROBOT_RUNTIME_DIR",
        str(Path(os.environ.get("LOCALAPPDATA", Path.home())) / "OrderAutomating"),
    )
)
PRICE_CACHE_PATH = RUNTIME_DIR / "last_seen_prices.json"
PRICE_ALERTS_PATH = RUNTIME_DIR / "price_alerts.txt"
HEADER_PATH = RUNTIME_DIR / "final_invoice_header.tsv"
ITEMS_PATH = RUNTIME_DIR / "final_invoice_items.tsv"
INVOICE_PATH = RUNTIME_DIR / "final_invoice.json"
ROBOT_PATH = PROJECT_DIR / "OrderRobot.ahk"



def load_gemini_keys() -> tuple[str, ...]:
    """Load comma-separated, legacy, and numbered Gemini keys without duplicates."""
    raw_values = [
        os.environ.get("GEMINI_API_KEYS", ""),
        os.environ.get("GEMINI_API_KEY", ""),
    ]
    for variable_name in sorted(os.environ):
        if re.fullmatch(r"GEMINI_API_KEY_\d+", variable_name):
            raw_values.append(os.environ.get(variable_name, ""))

    keys: list[str] = []
    for raw_value in raw_values:
        for key in raw_value.replace(";", ",").split(","):
            cleaned = key.strip()
            if cleaned and cleaned not in keys:
                keys.append(cleaned)
    return tuple(keys)


GEMINI_KEYS = load_gemini_keys()


def load_mistral_keys() -> tuple[str, ...]:
    """Load comma-separated, legacy, and numbered Mistral keys."""
    raw_values = [
        os.environ.get("MISTRAL_API_KEYS", ""),
        os.environ.get("MISTRAL_API_KEY", ""),
    ]
    for variable_name in sorted(os.environ):
        if re.fullmatch(r"MISTRAL_API_KEY_\d+", variable_name):
            raw_values.append(os.environ.get(variable_name, ""))

    keys: list[str] = []
    for raw_value in raw_values:
        for key in raw_value.replace(";", ",").split(","):
            cleaned = key.strip()
            if cleaned and cleaned not in keys:
                keys.append(cleaned)
    return tuple(keys)


MISTRAL_KEYS = load_mistral_keys()
MISTRAL_API_URL = os.environ.get("MISTRAL_API_URL", "https://api.mistral.ai/v1/ocr").strip()
MISTRAL_OCR_MODEL = os.environ.get("MISTRAL_OCR_MODEL", "mistral-ocr-latest").strip()
OCR_PROVIDER = os.environ.get("OCR_PROVIDER", "auto").strip().lower()
ENABLE_PHARMA_EXTRA_OCR_PASSES = os.environ.get(
    "ENABLE_PHARMA_EXTRA_OCR_PASSES", "false"
).strip().lower() in {"1", "true", "yes", "on"}
SERVER_TOKEN = os.environ.get("ORDER_ROBOT_TOKEN", "").strip()

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 20 * 1024 * 1024

# Model discovery is a network request. Keep the selected Flash model in
# memory so every invoice after the first one goes directly to OCR.
_MODEL_CACHE: dict[str, Any] = {}
_MODEL_CACHE_LOCK = Lock()


def get_ip() -> str:
    socket_handle = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        socket_handle.connect(("8.8.8.8", 1))
        return socket_handle.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        socket_handle.close()


def require_token() -> tuple[Any, int] | None:
    """Enable a shared secret by setting ORDER_ROBOT_TOKEN on the server."""
    if not SERVER_TOKEN:
        return None
    supplied = request.headers.get("X-Order-Robot-Token", "")
    if not hmac.compare_digest(supplied, SERVER_TOKEN):
        return jsonify({"error": "Unauthorized"}), 401
    return None


def atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    temporary_path.write_text(content, encoding="utf-8")
    temporary_path.replace(path)


def atomic_write_json(path: Path, value: Any) -> None:
    atomic_write_text(path, json.dumps(value, ensure_ascii=False, indent=2))


def as_finite_number(value: Any, field_name: str, default: float = 0.0) -> float:
    if value is None or value == "":
        return default
    try:
        number = float(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{field_name} must be numeric") from error
    if not math.isfinite(number):
        raise ValueError(f"{field_name} must be finite")
    return number


def build_ocr_prompt(supplier_code: str, column_hint: str) -> str:
    supplier_context = {
        "29": "Ibn Sina: sale_p is سعر الجمهور and may legitimately be blank; when blank return sale_p=0 and do not invent it. pharmacist_price is سعر الصيدلي. line_total_as_printed is الإجمالي بدون الضريبة. Android calculates purchase two ways: pharmacist_price + the TOP pharmacist number in هامش صيدلي وموزع, and (line_total_as_printed / paid quantity) + that same pharmacist margin. If tax_total (ضريبة ق.م for the row) is greater than zero, the valid pharmacist margin is zero. The lower stacked number is distributor margin and is never added. The خصم الصيدلي is informational only.",
        "38": "Pharma Overseas: extract the printed values only; Android performs both calculations. pharmacist_price must be the raw value under سعر صيدلي ج.م, pharmacist_margin must be the raw value under هامش ثابت للصيدلي, line_total_as_printed must be the raw value under إجمالي القيمة, quantity must be the paid quantity, and tax_per_item must be the raw value under ض.ق مضافة ج.م. IMPORTANT BUSINESS RULE: decide whether the row is taxed only from its per-unit ض.ق مضافة ج.م cell. When tax_per_item > 0 the valid pharmacist margin is zero. When tax_per_item is zero, extract and preserve هامش ثابت للصيدلي because it must be added to purchase price. tax_total alone never makes a row taxed. Never copy إجمالي ض.ق.م, tax_per_item, or another neighbouring value into pharmacist_margin. Keep these fields independent and never calculate or replace pharmacist_price from the line total. sale_p is سعر الجمهور. Ignore هامش ثابت للموزع and خصم الصيدلي in the purchase calculation, but extract them only into their own fields. Android verifies that pharmacist_price + the valid margin equals (line_total_as_printed / quantity - tax_per_item) + the valid margin. If they conflict, reread the original cells instead of forcing either value to match. Quantity is the number before a notation such as 2+1 and bonus is the number after it. IMPORTANT FOR NARROW PHARMA COLUMNS: a decimal value can wrap vertically inside the same cell, with the final fractional digit printed directly below the first line (for example 104.9 with a 6 underneath is 104.96). Inspect the complete cell and join only a digit that is visibly the continuation of that same value; never drop it, move it to the next row, or borrow it from an adjacent column.",
        "175": "Dream: unit_price is the pharmacy purchase cost even though the printed heading says sale price. Extract line_total_as_printed independently so Android can compare unit_price with line_total_as_printed / quantity. consumer_price is informational only; never set sale_p and never update the E-PLUS sale price.",
        "198": "United: there is no bonus and no margin; always return bonus=0, pharmacist_margin=0, and distributor_margin=0. unit_price and sale_p are the printed public sale price. line_total_as_printed divided by quantity is the authoritative purchase cost and Android compares it with public sale price after discount. Never copy location, serial, or another column into bonus.",
        "218": "Tabark/Multi Stores: there is no bonus and no margin; always return bonus=0, pharmacist_margin=0, and distributor_margin=0. The column ك immediately before the item name is the supplier item code, never a price. Never copy ك into unit_price or sale_p. The printed public sale price is only the value under the column س. بيع (after the stock/current-balance column), and must be copied to both unit_price and sale_p. line_total_as_printed divided by quantity is the authoritative purchase cost and Android compares it with public sale price after discount. For invoice_total_as_printed, read ONLY the final summary value explicitly labelled صافي الفاتورة. Never use الرصيد الحالي, الرصيد السابق, stock balance, customer balance, or any other balance as the invoice total. If the sale-price cell is unreadable, return sale_p=0 and unit_price=0 rather than guessing from ك, quantity, stock, or a neighbouring row.",
    }.get(
        supplier_code,
        """First identify the supplier from the invoice header. Supported layouts are:
Ibn Sina (29): سعر الجمهور is public sale, سعر الصيدلي plus only the top pharmacist
margin is purchase, and ضريبة ق.م is a row total divided by quantity; Pharma Overseas
(38): pharmacist price + fixed pharmacy margin; Dream (175): printed sale column is pharmacy cost;
United (198) and Tabark (218): unit_price is public sale price and line total is
purchase total. For Tabark/Multi Stores, a header containing تبارك فارما,
Tabark Pharma, Tabark, or مالتي ستورز means supplier 218. In the common Tabark
paper table, the narrow column ك before the name is only the supplier item code;
it is never a price. Read the sale price from the separate س. بيع column, read
quantity from الكمية, discount_percent from الخصم, and line_total_as_printed
from الاجمالي; do not swap these columns. If a row's candidate price equals its
ك code, treat that candidate as invalid and return 0 until the actual س. بيع
cell is read.
The printed كود العميل is the customer's code, not the internal supplier code,
and must never be returned as supplier_name. Keep every visible invoice row
exactly once, never merge or invent products, and preserve Arabic and English
item names as printed.""",
    )

    return f"""
Extract exactly one pharmacy purchase invoice as JSON. Do not invent values. A missing
numeric value must be 0. All numbers must be JSON numbers, never strings. Ignore
expiry dates and batch numbers in OCR; the pharmacist confirms expiry manually in
the review screen and the app sends only that confirmed MM/YY value.
invoice_total_as_printed must be the single final amount printed in the invoice
summary (labels such as صافي الفاتورة، الإجمالي المستحق، أو صافي الفاتورة بعد
الخصم). Never use a customer balance, previous balance, current stock, tax-only
total, invoice number, or a row total. Preserve the decimal point: if the paper
shows 6516.90, return 6516.90, never 651690. Read the summary digits as a complete
number before returning the JSON.

Selected supplier code: {supplier_code or "unknown"}
If a selected supplier code is present, it is authoritative for the column layout
and calculations; do not replace it with the printed customer code or a guessed
supplier name. If it is unknown, identify the supplier from the logo/header first.
Supplier rule: {supplier_context}
Column notes from the pharmacy: {column_hint or "none"}

Return ONLY this JSON schema:
{{
  "supplier_name": "",
  "invoice_number": "",
  "date": "",
  "invoice_total_as_printed": 0,
  "items": [{{
    "name": "",
    "quantity": 0,
    "bonus": 0,
    "unit_price": 0,
    "discount_percent": 0,
    "line_total_as_printed": 0,
    "pharmacist_price": 0,
    "pharmacist_margin": 0,
    "distributor_margin": 0,
    "tax_per_item": 0,
    "tax_total": 0,
    "sale_p": 0,
    "supplier_item_code": "",
    "consumer_price": 0,
    "invoice_margin_total": 0,
    "invoice_tax_total": 0
  }}]
}}

For code 218, map the Tabark columns exactly: ك -> supplier_item_code only (never
unit_price or sale_p), س. بيع -> unit_price and sale_p, and الإجمالي ->
line_total_as_printed. Codes 198 and 218 have no bonus and no margin: return zero
for bonus, pharmacist_margin, and distributor_margin. If the value in س. بيع cannot be read, use 0 instead of
the adjacent ك code. For code 29, map the Ibn Sina columns exactly: سعر الجمهور -> sale_p, سعر الصيدلي
-> pharmacist_price, خصم الصيدلي -> discount_percent (informational only), the TOP
number in هامش صيدلي وموزع -> pharmacist_margin, and ضريبة ق.م -> tax_total.
If the سعر الجمهور cell is blank for an Ibn Sina or Pharma Overseas row, return
sale_p=0 and consumer_price=0 for that row. Never copy سعر الصيدلي, unit_price,
line total, or purchase price into a missing public-price field.
The lower number in the stacked margin is the distributor margin and must be
ignored. Keep line_total_as_printed tied to the row's own printed إجمالي القيمة;
never reinterpret it as a summary total. Never use a summary total
from the bottom of the invoice as a row's pharmacist_margin or tax_total. If the
printed tax is 0, return tax_total=0 and tax_per_item=0. Do not derive a missing
row tax from the invoice grand total. For code 38, quantity is the paid number
before + and bonus is the free number after +; map the pharmacist margin,
distributor margin, and per-unit VAT separately. The printed إجمالي القيمة is
the row total including VAT and excluding fixed margins. Cross-check every row:
pharmacist_price = (إجمالي القيمة / paid quantity) - ض.ق مضافة ج.م. The fixed
pharmacist margin is added later by the app; do not put it inside
line_total_as_printed. For Pharma Overseas price/margin/tax cells, zoom in
mentally and read both lines of every narrow numeric cell. If the fractional part
continues below the decimal (for example 104.9 on the first line and 6 directly
under it), return the complete number 104.96. This continuation belongs to the
same row and column; do not interpret it as a separate row or as a margin from
another column. For Dream, put the printed pharmacy cost in
unit_price and the printed consumer/recommended price in consumer_price; set
sale_p to 0. For United and Tabark, put the printed public price in unit_price
and sale_p, and always extract the printed line total when present. Process every
row independently and preserve the visible row count; never copy values from a
different row or from the summary block.
""".strip()


MISTRAL_INVOICE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "supplier_name": {"type": "string"},
        "invoice_number": {"type": "string"},
        "date": {"type": "string"},
        "invoice_total_as_printed": {"type": "number"},
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "name": {"type": "string"},
                    "quantity": {"type": "number"},
                    "bonus": {"type": "number"},
                    "unit_price": {"type": "number"},
                    "discount_percent": {"type": "number"},
                    "line_total_as_printed": {"type": "number"},
                    "pharmacist_price": {"type": "number"},
                    "pharmacist_margin": {"type": "number"},
                    "distributor_margin": {"type": "number"},
                    "tax_per_item": {"type": "number"},
                    "tax_total": {"type": "number"},
                    "sale_p": {"type": "number"},
                    "supplier_item_code": {"type": "string"},
                    "consumer_price": {"type": "number"},
                    "invoice_margin_total": {"type": "number"},
                    "invoice_tax_total": {"type": "number"},
                },
                "required": [
                    "name",
                    "quantity",
                    "bonus",
                    "unit_price",
                    "discount_percent",
                    "line_total_as_printed",
                    "pharmacist_price",
                    "pharmacist_margin",
                    "distributor_margin",
                    "tax_per_item",
                    "tax_total",
                    "sale_p",
                    "supplier_item_code",
                    "consumer_price",
                    "invoice_margin_total",
                    "invoice_tax_total",
                ],
            },
        },
    },
    "required": ["supplier_name", "invoice_number", "date", "invoice_total_as_printed", "items"],
}


def _mistral_document_reference(encoded_data: str, mime_type: str) -> dict[str, str]:
    data_url = f"data:{mime_type};base64,{encoded_data}"
    if mime_type == "application/pdf":
        return {"type": "document_url", "document_url": data_url}
    return {"type": "image_url", "image_url": data_url}


def build_pharma_columns_crop(encoded_data: str, mime_type: str) -> bytes | None:
    """Enlarge Pharma's narrow numeric half so small margins remain legible."""
    if Image is None or mime_type not in {"image/jpeg", "image/png"}:
        return None
    try:
        with Image.open(BytesIO(base64.b64decode(encoded_data))) as source:
            image = source.convert("RGB")
            width, height = image.size
            # All numeric columns, including the far-left fixed margins and
            # pharmacist price, occupy the left portion of this layout.
            crop = image.crop(
                (
                    max(0, int(width * 0.07)),
                    max(0, int(height * 0.29)),
                    min(width, int(width * 0.62)),
                    min(height, int(height * 0.73)),
                )
            )
            crop = crop.resize((crop.width * 3, crop.height * 3))
            crop = ImageEnhance.Contrast(crop).enhance(1.45)
            crop = ImageEnhance.Sharpness(crop).enhance(1.8)
            crop = crop.filter(ImageFilter.SHARPEN)
            output = BytesIO()
            crop.save(output, format="JPEG", quality=95)
            return output.getvalue()
    except Exception:
        app.logger.warning("Could not create enlarged Pharma column crop")
        return None


def call_mistral_ocr(
    api_key: str,
    encoded_data: str,
    mime_type: str,
    prompt: str,
) -> dict[str, Any]:
    """Run Mistral OCR with a strict annotation schema and return our OCR shape."""
    body = {
        "model": MISTRAL_OCR_MODEL,
        "document": _mistral_document_reference(encoded_data, mime_type),
        "document_annotation_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "pharmacy_invoice",
                "schema": MISTRAL_INVOICE_SCHEMA,
                "strict": True,
            },
        },
        "document_annotation_prompt": prompt,
        "table_format": "html",
    }
    request_body = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = Request(
        MISTRAL_API_URL,
        data=request_body,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )

    try:
        with urlopen(request, timeout=75) as response:
            response_body = response.read().decode("utf-8")
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:400]
        raise RuntimeError(f"Mistral HTTP {error.code}: {detail}") from error
    except URLError as error:
        raise RuntimeError(f"Mistral connection failed: {error.reason}") from error

    try:
        result = json.loads(response_body)
    except json.JSONDecodeError as error:
        raise RuntimeError("Mistral returned invalid JSON") from error

    annotation = result.get("document_annotation") if isinstance(result, dict) else None
    if isinstance(annotation, str):
        payload = clean_json_response(annotation)
    elif isinstance(annotation, dict):
        payload = annotation
    else:
        raise RuntimeError("Mistral response did not contain document_annotation")

    if not isinstance(payload.get("items"), list):
        raise RuntimeError("Mistral annotation did not contain an invoice items list")
    return payload


def choose_model(api_key: str) -> Any:
    if genai is None:
        raise RuntimeError("google-generativeai is not installed on the server")

    with _MODEL_CACHE_LOCK:
        cached = _MODEL_CACHE.get(api_key)
    if cached is not None:
        return cached

    genai.configure(api_key=api_key)
    configured_name = os.environ.get("GEMINI_MODEL", "").strip()
    if configured_name:
        preferred = configured_name
    else:
        names = [
            model.name
            for model in genai.list_models()
            if "generateContent" in model.supported_generation_methods
        ]
        if not names:
            raise RuntimeError("No Gemini content-generation model is available")
        preferred = next((name for name in names if "flash" in name.lower()), names[0])

    model = genai.GenerativeModel(preferred)
    with _MODEL_CACHE_LOCK:
        _MODEL_CACHE[api_key] = model
    return model


def clean_json_response(text: str) -> dict[str, Any]:
    cleaned = text.replace("```json", "").replace("```", "").strip()
    payload = json.loads(cleaned)
    if not isinstance(payload, dict) or not isinstance(payload.get("items"), list):
        raise ValueError("Gemini response did not contain an invoice items list")
    return payload


def _as_payload_number(value: Any) -> float:
    """Read a draft OCR number without allowing malformed values to crash review."""
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0.0
    return number if math.isfinite(number) else 0.0


def _is_gemini_quota_error(error: BaseException) -> bool:
    """Recognise quota/rate-limit failures without depending on SDK internals."""
    error_name = type(error).__name__.lower()
    message = str(error).lower()
    return any(
        marker in error_name or marker in message
        for marker in ("resourceexhausted", "quota", "rate limit", "429")
    )


def _needs_tabark_price_review(payload: dict[str, Any], supplier_code: str) -> bool:
    """Detect the characteristic Tabark column swap before sending OCR to Android.

    The narrow ``ك`` column is printed immediately before the product name. Gemini
    occasionally mistakes it for the adjacent public-price column, most visibly on
    the first row. A second, focused visual pass is cheap compared with sending a
    wrong price into E-PLUS, so only suspicious drafts are retried.
    """
    if supplier_code not in {"218", "198"}:
        return False
    items = payload.get("items")
    if not isinstance(items, list) or not items:
        return False

    for item in items:
        if not isinstance(item, dict):
            continue
        sale_price = _as_payload_number(item.get("sale_p"))
        unit_price = _as_payload_number(item.get("unit_price"))
        code_text = str(item.get("supplier_item_code", "")).strip().replace(",", "")
        code_number = _as_payload_number(code_text) if code_text.isdigit() else 0.0

        # Explicit equality is the strongest signal and works for codes such as
        # 660, 482 and 72 without relying on a hard-coded product list.
        if code_number > 0 and any(
            math.isclose(candidate, code_number, abs_tol=0.0005)
            for candidate in (sale_price, unit_price)
            if candidate > 0
        ):
            return True

        # Older prompts did not request supplier_item_code. Keep a conservative
        # fallback for the exact failure seen in Tabark invoices: a whole-number
        # value in the code-sized range, or a missing public price.
        if supplier_code == "218" and (
            sale_price <= 0
            or unit_price <= 0
            or (sale_price >= 500 and sale_price.is_integer())
        ):
            return True
    return False


def build_price_review_prompt(base_prompt: str, draft: dict[str, Any]) -> str:
    draft_json = json.dumps(draft, ensure_ascii=False, separators=(",", ":"))
    return f"""{base_prompt}

SECOND-PASS PRICE CHECK (the first draft below was flagged): reread the original
invoice image row by row. For Tabark/Multi Stores, the column ك immediately before
اسم الصنف is only supplier_item_code. It is never a price. The public sale price
must come only from the separate س. بيع column. Keep each row aligned with its own
name, quantity, and printed total; never borrow a number from the row above or
below. If س. بيع is genuinely unreadable, output 0 rather than guessing. Return
the complete corrected JSON object using the exact schema above, with no commentary.

First-pass draft for verification only:
{draft_json}"""


def build_pharma_values_review_prompt(base_prompt: str, draft: dict[str, Any]) -> str:
    """Final, focused reread of the narrow numeric columns in Pharma invoices."""
    draft_json = json.dumps(draft, ensure_ascii=False, separators=(",", ":"))
    return f"""{base_prompt}

FINAL PHARMA OVERSEAS NUMERIC-COLUMN REVIEW: inspect the original image again and
return the complete JSON object. Preserve row order and item count. Concentrate
only on these printed columns and never move a value between them:
- خصم الصيدلي -> discount_percent only.
- سعر صيدلي ج.م -> pharmacist_price only. It is never the discount percentage.
  For example, a row may print discount 20 while pharmacist price is 66.67 or
  42.11; do not output 20 as pharmacist_price and do not calculate this field.
- ض.ق مضافة ج.م -> tax_per_item only.
- إجمالي ض.ق.م -> tax_total only.
- هامش ثابت للصيدلي -> pharmacist_margin only. This is the narrow far-left
  pharmacist-margin column, not tax_total and not discount_percent.

If tax_per_item is greater than zero, pharmacist_margin must be zero because a
taxed Pharma item has no fixed pharmacist margin. If tax_per_item is zero,
preserve the actually printed far-left margin, including small values such as
2.5, 1.5, or 1. Complete vertically wrapped decimals (104.9 with 6 directly
under it means 104.96). Do not derive pharmacist_price from totals; Android will
perform and compare both calculations. Return JSON only, without commentary.

Use the bottom summary labelled إجمالي قيمة الهامش الثابت للصيدلي as a strict
cross-check: for all non-tax rows, the sum of pharmacist_margin multiplied by
each paid quantity must equal that printed summary total. If it does not, zoom
into the far-left margin cells again; do not return zero merely because the text
is faint. In the attached enlarged crop each margin stays aligned horizontally
with its own row.
The LAST attached image is an isolated 5x enlargement of only the two margin
columns. Read pharmacist_margin from its right-hand margin column, row by row.
Do not return zero for a faint cell until checking this isolated image.

First-pass draft for row identity and verification:
{draft_json}"""


def build_tabark_total_review_prompt(base_prompt: str, draft: dict[str, Any]) -> str:
    """Force an independent visual reread of Tabark's final net invoice total."""
    draft_json = json.dumps(draft, ensure_ascii=False, separators=(",", ":"))
    return f"""{base_prompt}

SECOND-PASS TABARK INVOICE-TOTAL CHECK: ignore the draft total and inspect the
original invoice summary at the bottom of the document. invoice_total_as_printed
must come ONLY from the numeric value printed beside the exact label صافي الفاتورة.
Do not use الرصيد الحالي, الرصيد السابق, رصيد العميل, stock/current balance,
customer balance, إجمالي سابق, a row total, or any number from an item row.
Read all digits and the decimal separator from the صافي الفاتورة cell itself.
Return the complete JSON object using the exact schema above. Preserve the item
rows from the draft unless a value must be repeated to satisfy the schema; this
pass is for the final invoice total only.

First-pass draft for reference:
{draft_json}"""


def _pharma_implied_pharmacist_price(item: dict[str, Any]) -> float:
    """Derive Pharma Overseas pharmacist price from the row's own totals."""
    quantity = _as_payload_number(item.get("quantity"))
    line_total = _as_payload_number(item.get("line_total_as_printed"))
    if quantity <= 0 or line_total <= 0:
        return 0.0
    tax_per_item = _as_payload_number(item.get("tax_per_item"))
    if tax_per_item <= 0:
        tax_total = _as_payload_number(item.get("tax_total"))
        tax_per_item = tax_total / quantity if tax_total > 0 else 0.0
    return max(0.0, line_total / quantity - tax_per_item)


def repair_pharma_payload(payload: dict[str, Any], supplier_code: str) -> dict[str, Any]:
    """Keep code-38 OCR fields raw so Android can compare both calculations."""
    # Do not replace pharmacist_price with the value implied by line_total, and
    # do not substitute another margin column. The second OCR pass may reread
    # inconsistent cells, but the returned fields must remain independent.
    return payload


def repair_distributor_invoice_total(
    payload: dict[str, Any], supplier_code: str
) -> dict[str, Any]:
    """Reject a balance misread as the net total for United/Tabark invoices.

    These suppliers have neither bonus nor fixed margins, so complete row totals
    must add up to the net invoice. We replace the extracted summary only when
    every row independently reconciles with quantity, public price and discount,
    and the summary disagreement is above the app's one-pound tolerance.
    """
    if supplier_code not in {"198", "218"}:
        return payload
    items = payload.get("items")
    if not isinstance(items, list) or not items:
        return payload

    calculated_total = 0.0
    for item in items:
        if not isinstance(item, dict):
            return payload
        quantity = _as_payload_number(item.get("quantity"))
        line_total = _as_payload_number(item.get("line_total_as_printed"))
        public_price = _as_payload_number(item.get("sale_p")) or _as_payload_number(
            item.get("unit_price")
        )
        discount = _as_payload_number(item.get("discount_percent"))
        if quantity <= 0 or line_total <= 0 or public_price <= 0:
            return payload
        expected_line_total = public_price * quantity * (1.0 - discount / 100.0)
        line_tolerance = max(0.10, line_total * 0.005)
        if abs(expected_line_total - line_total) > line_tolerance:
            return payload
        calculated_total += line_total

    calculated_total = round(calculated_total, 3)
    extracted_total = _as_payload_number(payload.get("invoice_total_as_printed"))
    if extracted_total <= 0 or abs(extracted_total - calculated_total) > 1.0:
        app.logger.warning(
            "Supplier %s summary total %.3f rejected; reconciled row total is %.3f",
            supplier_code,
            extracted_total,
            calculated_total,
        )
        payload["invoice_total_as_printed"] = calculated_total
    return payload


def merge_pharma_margin_candidates(
    payload: dict[str, Any],
    candidates: list[dict[str, Any]],
    supplier_code: str,
) -> dict[str, Any]:
    """Preserve a valid Pharma margin found by any visual pass."""
    if supplier_code != "38":
        return payload
    final_items = payload.get("items")
    if not isinstance(final_items, list) or not final_items:
        return payload

    aligned_candidates = [
        candidate.get("items")
        for candidate in candidates
        if isinstance(candidate, dict)
        and isinstance(candidate.get("items"), list)
        and len(candidate["items"]) == len(final_items)
    ]

    for index, final_item in enumerate(final_items):
        if not isinstance(final_item, dict):
            continue
        if _as_payload_number(final_item.get("tax_per_item")) > 0:
            final_item["pharmacist_margin"] = 0
            continue

        row_versions = [
            items[index]
            for items in aligned_candidates
            if isinstance(items[index], dict)
        ]
        row_versions.append(final_item)

        def valid_margin(value: Any, row: dict[str, Any]) -> float:
            number = _as_payload_number(value)
            if number <= 0:
                return 0.0
            collisions = (
                row.get("tax_per_item"),
                row.get("tax_total"),
                row.get("invoice_tax_total"),
                row.get("discount_percent"),
            )
            if any(
                _as_payload_number(collision) > 0
                and math.isclose(number, _as_payload_number(collision), abs_tol=0.01)
                for collision in collisions
            ):
                return 0.0
            return number

        # A later zero reread must never erase a positive pharmacist margin
        # found by an earlier visual pass.
        margin = next(
            (
                value
                for row in row_versions
                if (value := valid_margin(row.get("pharmacist_margin"), row)) > 0
            ),
            0.0,
        )
        # Exact observed Pharma shift: faint margins may move into the adjacent
        # distributor field. Use it only if no pass read pharmacist_margin.
        if margin <= 0:
            margin = next(
                (
                    value
                    for row in row_versions
                    if (value := valid_margin(row.get("distributor_margin"), row)) > 0
                ),
                0.0,
            )
        final_item["pharmacist_margin"] = margin
    return payload


def _needs_pharma_column_review(payload: dict[str, Any], supplier_code: str) -> bool:
    """Detect a shifted pharmacist/discount/margin column in code 38 drafts."""
    if supplier_code != "38":
        return False
    items = payload.get("items")
    if not isinstance(items, list) or not items:
        return False

    for item in items:
        if not isinstance(item, dict):
            continue
        implied = _pharma_implied_pharmacist_price(item)
        extracted = _as_payload_number(item.get("pharmacist_price"))
        if implied > 0 and (extracted <= 0 or abs(implied - extracted) >= 0.50):
            return True

        discount = _as_payload_number(item.get("discount_percent"))
        margin = _as_payload_number(item.get("pharmacist_margin"))
        if discount > 0 and math.isclose(margin, discount, abs_tol=0.0005):
            return True

        quantity = _as_payload_number(item.get("quantity"))
        tax_per_item = _as_payload_number(item.get("tax_per_item"))
        tax_total = _as_payload_number(item.get("tax_total"))
        expected_tax_total = tax_per_item * quantity
        if tax_per_item > 0 and tax_total > 0 and not math.isclose(
            expected_tax_total, tax_total, abs_tol=0.49
        ):
            return True
        if margin > 0 and (
            (tax_total > 0 and math.isclose(margin, tax_total, abs_tol=0.01))
            or (expected_tax_total > 0 and math.isclose(margin, expected_tax_total, abs_tol=0.01))
        ):
            return True
    return False


def build_pharma_column_review_prompt(
    base_prompt: str, draft: dict[str, Any]
) -> str:
    draft_json = json.dumps(draft, ensure_ascii=False, separators=(",", ":"))
    return f"""{base_prompt}

SECOND-PASS PHARMA COLUMN CHECK (the first draft was mathematically inconsistent):
reread every original Pharma Overseas row at the highest available visual detail.
Do not use the خصم الصيدلي column as pharmacist_price or pharmacist_margin. Read
هامش ثابت للصيدلي and هامش ثابت للموزع only from their own printed headings at the
far left. For code 38, إجمالي القيمة is the row amount including row VAT but
excluding the fixed margins, so verify each row with:
pharmacist_price = (إجمالي القيمة / paid quantity) - ض.ق مضافة ج.م.
Keep the fixed pharmacist margin separate; the app adds it after extracting the
base pharmacist price. Return the raw printed pharmacist_price unchanged; do not
replace it with the value calculated from إجمالي القيمة. Preserve the complete wrapped decimals (104.9 with a 6
directly beneath it means 104.96). Keep every row aligned and return the complete
corrected JSON object using the exact schema, with no commentary.

Also verify independently that tax_per_item × paid quantity equals tax_total.
The column إجمالي ض.ق.م belongs only in tax_total and must never be copied into
pharmacist_margin. pharmacist_margin must come only from هامش ثابت للصيدلي near
the far-left edge; a printed zero must remain zero. Only tax_per_item > 0 makes
a Pharma Overseas row taxed and forces pharmacist_margin=0. When tax_per_item
is zero, preserve the printed pharmacist margin. tax_total alone must not zero
the margin. If a draft margin equals the
row VAT total, reread both cells because the columns have probably shifted.

First-pass draft for verification only:
{draft_json}"""


def _has_single_decimal_digit(value: Any) -> bool:
    """Return True for a non-integer value represented with one decimal digit.

    Pharma Overseas invoices sometimes print the final fractional digit on a
    second line because the price column is narrow. A first-pass value such as
    104.9 is therefore a useful, conservative signal for a focused visual retry.
    """
    number = _as_payload_number(value)
    if number <= 0 or number.is_integer():
        return False
    return math.isclose(number * 10.0, round(number * 10.0), abs_tol=1e-7)


def _needs_pharma_decimal_review(payload: dict[str, Any], supplier_code: str) -> bool:
    """Detect a likely dropped continuation digit in Pharma price columns."""
    if supplier_code != "38":
        return False
    items = payload.get("items")
    if not isinstance(items, list) or not items:
        return False

    numeric_fields = (
        "unit_price",
        "pharmacist_price",
        "pharmacist_margin",
        "tax_per_item",
        "tax_total",
        "sale_p",
        "line_total_as_printed",
    )
    return any(
        isinstance(item, dict)
        and any(_has_single_decimal_digit(item.get(field)) for field in numeric_fields)
        for item in items
    )


def build_pharma_decimal_review_prompt(
    base_prompt: str, draft: dict[str, Any]
) -> str:
    draft_json = json.dumps(draft, ensure_ascii=False, separators=(",", ":"))
    return f"""{base_prompt}

SECOND-PASS PHARMA NUMBER CHECK (the first draft may have lost a digit): reread
the original Pharma Overseas invoice at the highest available visual detail,
row by row. The narrow numeric columns can wrap the last decimal digit directly
under the first line. For every price, margin, tax, and printed line-total cell,
inspect the line immediately below before finalizing the number. If the same cell
shows 104.9 on the first line and a continuation digit 6 directly underneath,
return 104.96. Join only a visibly aligned continuation from the same cell; do
not append digits from the next row or neighboring column. Preserve genuine
one-decimal values when no continuation is printed. Return the complete corrected
JSON object using the exact schema above, with no commentary.

First-pass draft for verification only:
{draft_json}"""


def _normalise_pdf_text(text: str) -> str:
    """Make Arabic presentation forms and PDF spacing usable by the detectors."""
    normalised = unicodedata.normalize("NFKC", text or "")
    # Arabic PDFs frequently use Persian Yeh and Heh variants in one copy and
    # standard Arabic letters in another. Convert the common variants before
    # looking for invoice labels.
    normalised = normalised.translate(str.maketrans({
        "ی": "ي",
        "ى": "ي",
        "ھ": "ه",
        "ۀ": "ه",
        "ة": "ه",
    }))
    return re.sub(r"\s+", " ", normalised).strip()


def _number_near_marker(text: str, markers: tuple[str, ...]) -> str:
    for marker in markers:
        marker_index = text.find(marker)
        if marker_index == -1:
            continue
        around = text[max(0, marker_index - 48): marker_index + 48]
        numbers = re.findall(r"(?<!\d)\d{4,}(?!\d)", around)
        if numbers:
            return numbers[0]
    return ""


def _extract_pdf_invoice_number(text: str) -> str:
    normalised = _normalise_pdf_text(text)
    return _number_near_marker(
        normalised,
        (
            "رقم الحركة",
            "رقم الحركه",
            "رقم الفاتورة",
            "رقم الفاتوره",
            "invoice number",
            "invoice no",
            "statement number",
        ),
    )


def _extract_pdf_integer(text: str, marker: str) -> int:
    normalised = _normalise_pdf_text(text)
    marker_index = normalised.find(marker)
    if marker_index == -1:
        return 0
    around = normalised[max(0, marker_index - 24): marker_index + 48]
    numbers = re.findall(r"(?<!\d)\d{1,5}(?!\d)", around)
    return int(numbers[0]) if numbers else 0


def _extract_pdf_total(text: str) -> float:
    normalised = _normalise_pdf_text(text)
    for marker in (
        "قيمة الفاتورة",
        "قيمه الفاتوره",
        "اجمالي الفاتورة",
        "اجمالي الفاتوره",
        "إجمالي الفاتورة",
        "invoice total",
    ):
        marker_index = normalised.find(marker)
        if marker_index == -1:
            continue
        around = normalised[marker_index: marker_index + 100]
        numbers = re.findall(r"\d+[.,]\d{1,2}", around)
        for value in numbers:
            try:
                parsed = float(value.replace(",", ""))
            except ValueError:
                continue
            if parsed > 0:
                return parsed
    return 0.0


def _is_two_up_page(text: str, width: float, height: float) -> bool:
    """Detect two identical receipt copies printed side-by-side on landscape A4."""
    if height <= 0 or width / height < 1.25:
        return False
    normalised = _normalise_pdf_text(text)
    duplicate_markers = (
        "أمر بيع",
        "رقم الحركة",
        "عدد الأصناف",
        "قيمة الفاتورة",
        "الحساب السابق",
    )
    return sum(normalised.count(marker) for marker in duplicate_markers) >= 2


def inspect_pdf_bytes(pdf_bytes: bytes) -> list[dict[str, Any]]:
    """Group a multi-invoice PDF by printed invoice number and continuation pages."""
    if PdfReader is None:
        raise RuntimeError("pypdf is required for multi-invoice PDF inspection")
    reader = PdfReader(BytesIO(pdf_bytes))
    if not reader.pages:
        raise ValueError("The PDF does not contain any pages")

    pages: list[dict[str, Any]] = []
    for index, page in enumerate(reader.pages, start=1):
        try:
            text = page.extract_text() or ""
        except Exception:
            text = ""
        width = float(page.mediabox.width)
        height = float(page.mediabox.height)
        pages.append(
            {
                "page": index,
                "invoice_number": _extract_pdf_invoice_number(text),
                "item_count": _extract_pdf_integer(text, "عدد الأصناف"),
                "total": _extract_pdf_total(text),
                "two_up": _is_two_up_page(text, width, height),
            }
        )

    groups: list[dict[str, Any]] = []
    for page in pages:
        invoice_number = page["invoice_number"]
        if groups and (not invoice_number or invoice_number == groups[-1]["invoice_number"]):
            groups[-1]["pages"].append(page)
        else:
            groups.append(
                {
                    "invoice_number": invoice_number or f"صفحات {page['page']}",
                    "pages": [page],
                }
            )

    candidates: list[dict[str, Any]] = []
    for group in groups:
        group_pages = group["pages"]
        start = group_pages[0]["page"]
        end = group_pages[-1]["page"]
        item_count = next(
            (page["item_count"] for page in group_pages if page["item_count"] > 0),
            0,
        )
        total = next(
            (page["total"] for page in reversed(group_pages) if page["total"] > 0),
            0.0,
        )
        duplicate_copies = 2 if any(page["two_up"] for page in group_pages) else 1
        candidates.append(
            {
                "invoice_number": group["invoice_number"],
                "page_start": start,
                "page_end": end,
                "item_count": item_count,
                "printed_total": total,
                "duplicate_copies": duplicate_copies,
                "side": "left" if duplicate_copies > 1 else "full",
            }
        )
    return candidates


def select_pdf_pages(pdf_bytes: bytes, page_start: int, page_end: int, side: str) -> bytes:
    """Create a small PDF containing only the selected invoice pages/copy."""
    if PdfReader is None or PdfWriter is None or Transformation is None or RectangleObject is None:
        raise RuntimeError("pypdf is required for selecting PDF invoice pages")
    reader = PdfReader(BytesIO(pdf_bytes))
    total_pages = len(reader.pages)
    if page_start < 1 or page_end < page_start or page_end > total_pages:
        raise ValueError("Invalid PDF page range")

    writer = PdfWriter()
    for index in range(page_start - 1, page_end):
        page = reader.pages[index]
        if side in {"left", "right"}:
            width = float(page.mediabox.width)
            height = float(page.mediabox.height)
            half = width / 2.0
            box = (0, 0, half, height) if side == "left" else (half, 0, width, height)
            if side == "right":
                # Move the right copy back to the origin before shrinking the
                # media box; otherwise its content would sit outside the page.
                page.add_transformation(Transformation().translate(tx=-half, ty=0))
                box = (0, 0, half, height)
            # CropBox is honored by PDF renderers and preserves the original
            # vector text, unlike rendering the page to a low-resolution image.
            page.mediabox = RectangleObject(box)
            page.cropbox = RectangleObject(box)
            page.trimbox = RectangleObject(box)
        writer.add_page(page)

    output = BytesIO()
    writer.write(output)
    return output.getvalue()


def load_price_cache() -> dict[str, dict[str, float]]:
    try:
        decoded = json.loads(PRICE_CACHE_PATH.read_text(encoding="utf-8"))
        if not isinstance(decoded, dict):
            return {}
        # Ignore malformed entries instead of letting a damaged cache stop the
        # whole invoice after the user has already reviewed it.
        return {
            str(key): value
            for key, value in decoded.items()
            if isinstance(value, dict)
        }
    except (OSError, json.JSONDecodeError):
        return {}


def format_price_alerts(
    supplier_code: str, invoice_number: str, items: list[dict[str, Any]]
) -> str:
    cache = load_price_cache()
    alerts: list[str] = []

    for item in items:
        item_code = item["itm_code"]
        # كود الصنف قد يتكرر بين موردين، لذلك لا نخلط سعر مورد بسعر مورد آخر.
        cache_key = f"{supplier_code}:{item_code}"
        alert_kind = item["price_alert_kind"]
        value_key = "purchase_price" if alert_kind == "purchase_price" else "sale_price"
        new_value = item["price"] if value_key == "purchase_price" else item["sale_price"]
        if new_value <= 0:
            continue

        previous = cache.get(cache_key, {}).get(value_key)
        if isinstance(previous, (int, float)) and not math.isclose(previous, new_value, abs_tol=0.004):
            direction = "↑" if new_value > previous else "↓"
            label = "سعر الشراء" if value_key == "purchase_price" else "سعر البيع"
            alerts.append(
                f"{direction} {item['invoice_name']} [{item_code}] - {label}: "
                f"{previous:.3f} ← {new_value:.3f}"
            )
        elif previous is None and supplier_code == "175":
            # دريم يحتاج تقريراً مفيداً حتى في أول تشغيل، لأن المستخدم يريد
            # مراجعة تكلفة كل الأصناف ورفع سعر البيع بنفسه عند الحاجة.
            alerts.append(
                f"• {item['invoice_name']} [{item_code}] - سعر الشراء جديد: {new_value:.3f}"
            )

        cache.setdefault(cache_key, {})[value_key] = new_value

    atomic_write_json(PRICE_CACHE_PATH, cache)
    heading = f"تغيّرات الأسعار | مورد {supplier_code} | فاتورة {invoice_number}"
    if not alerts:
        return f"{heading}\nلا توجد تغيّرات مقارنة بآخر فاتورة عالجها الروبوت."
    return "\n".join([heading, f"عدد التغيّرات: {len(alerts)}", *alerts])


def normalise_expiry(month_value: Any, year_value: Any, index: int) -> tuple[str, str]:
    """Validate the manually confirmed MM/YY expiry sent by the app.

    OCR never fills this value. An empty pair means the pharmacist chose to
    leave the date for a manual decision in E-PLUS; a partial or past date is
    rejected before anything reaches the robot.
    """
    month_text = str(month_value or "").strip()
    year_text = str(year_value or "").strip()
    if not month_text and not year_text:
        return "", ""
    if not month_text or not year_text:
        raise ValueError(f"الصنف {index}: أدخل شهر وسنة الصلاحية معًا")
    if not re.fullmatch(r"\d{2}", month_text) or not re.fullmatch(r"\d{2}", year_text):
        raise ValueError(f"الصنف {index}: الصلاحية يجب أن تكون بصيغة MM/YY من رقمين")

    month = int(month_text)
    short_year = int(year_text)
    if month not in range(1, 13):
        raise ValueError(f"الصنف {index}: شهر الصلاحية غير صحيح")
    full_year = 2000 + short_year
    today = date.today()
    if (full_year, month) <= (today.year, today.month):
        raise ValueError(f"الصنف {index}: تاريخ الصلاحية يجب أن يكون بعد الشهر الحالي")
    return f"{month:02d}", f"{short_year:02d}"


def normalise_invoice(payload: dict[str, Any]) -> tuple[str, str, list[dict[str, Any]]]:
    supplier_code = str(payload.get("supplier_code", "")).strip()
    invoice_number = str(payload.get("invoice_number", "")).strip()
    source_items = payload.get("items")
    if not supplier_code or not invoice_number:
        raise ValueError("supplier_code and invoice_number are required")
    if not isinstance(source_items, list) or not source_items:
        raise ValueError("at least one invoice item is required")

    items: list[dict[str, Any]] = []
    for index, source in enumerate(source_items, start=1):
        if not isinstance(source, dict):
            raise ValueError(f"item {index} is invalid")
        item_code = str(source.get("itm_code", "")).strip()
        if not item_code or len(item_code) > 64:
            raise ValueError(f"item {index} has an invalid itm_code")
        quantity = as_finite_number(source.get("quantity"), f"item {index} quantity")
        if quantity <= 0 or quantity != int(quantity):
            raise ValueError(f"item {index} quantity must be a positive integer")
        bonus = as_finite_number(source.get("bonus"), f"item {index} bonus")
        if bonus < 0 or bonus != int(bonus):
            raise ValueError(f"item {index} bonus must be a non-negative integer")

        # قواعد المورد لا تعتمد على عميل يمكن العبث به. دريم لا يغيّر سعر البيع
        # مهما كانت القيمة القادمة من الهاتف، وبقية الموردين الحاليين يتابعون
        # تغيّر سعر البيع.
        alert_kind = "purchase_price" if supplier_code == "175" else "sale_price"

        taxes = as_finite_number(source.get("taxes"), f"item {index} taxes")
        purchase_price = as_finite_number(source.get("price"), f"item {index} price")
        sale_price = as_finite_number(source.get("sale_price"), f"item {index} sale_price")
        if taxes < 0 or purchase_price < 0 or sale_price < 0:
            raise ValueError(f"item {index} prices and taxes cannot be negative")
        expiry_month, expiry_year = normalise_expiry(
            source.get("expiry_month"), source.get("expiry_year"), index
        )

        items.append(
            {
                "itm_code": item_code,
                "quantity": int(quantity),
                "bonus": int(bonus),
                "taxes": taxes,
                "price": purchase_price,
                "sale_price": sale_price,
                "update_sale_price": supplier_code != "175" and bool(source.get("update_sale_price", True)),
                "price_alert_kind": alert_kind,
                "invoice_name": str(source.get("invoice_name", item_code)).replace("\t", " ").replace("\n", " ").strip(),
                "expiry_month": expiry_month,
                "expiry_year": expiry_year,
            }
        )
    return supplier_code, invoice_number, items


def write_robot_files(supplier_code: str, invoice_number: str, items: list[dict[str, Any]]) -> str:
    # JSON is retained for troubleshooting. The tab-separated files avoid parsing
    # JSON with regular expressions inside AutoHotkey.
    atomic_write_json(
        INVOICE_PATH,
        {"supplier_code": supplier_code, "invoice_number": invoice_number, "items": items},
    )
    atomic_write_text(HEADER_PATH, f"{supplier_code}\t{invoice_number}\n")
    rows = [
        "\t".join(
            [
                item["itm_code"],
                str(item["quantity"]),
                str(item["bonus"]),
                str(item["taxes"]),
                str(item["price"]),
                str(item["sale_price"]),
                "1" if item["update_sale_price"] else "0",
                item.get("expiry_month", ""),
                item.get("expiry_year", ""),
            ]
        )
        for item in items
    ]
    atomic_write_text(ITEMS_PATH, "\n".join(rows) + "\n")
    report = format_price_alerts(supplier_code, invoice_number, items)
    atomic_write_text(PRICE_ALERTS_PATH, report)
    return report


@app.get("/")
def home() -> str:
    return f"Pharmacy automation server online | {get_ip()}:8080"


def _decode_invoice_payload(data: dict[str, Any]) -> bytes:
    encoded = data.get("data")
    if not isinstance(encoded, str) or not encoded:
        raise ValueError("A base64 invoice payload is required")
    try:
        return base64.b64decode(encoded, validate=False)
    except (ValueError, TypeError) as error:
        raise ValueError("The invoice payload is not valid base64") from error


@app.post("/pdf-inspect")
def inspect_pdf() -> tuple[Any, int] | Any:
    auth_error = require_token()
    if auth_error:
        return auth_error
    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        return jsonify({"error": "A PDF JSON payload is required"}), 400
    try:
        pdf_bytes = _decode_invoice_payload(data)
        candidates = inspect_pdf_bytes(pdf_bytes)
        return jsonify({"pages": sum(1 for _ in PdfReader(BytesIO(pdf_bytes)).pages), "invoices": candidates})
    except (ValueError, RuntimeError) as error:
        return jsonify({"error": str(error)}), 400
    except Exception:
        app.logger.exception("Could not inspect the invoice PDF")
        return jsonify({"error": "Could not inspect the invoice PDF"}), 500


@app.post("/gemini")
def process_ocr() -> tuple[Any, int] | Any:
    auth_error = require_token()
    if auth_error:
        return auth_error
    if not GEMINI_KEYS and not MISTRAL_KEYS:
        return jsonify({"error": "Configure GEMINI_API_KEY(S) or MISTRAL_API_KEY on the server"}), 503

    data = request.get_json(silent=True)
    if not isinstance(data, dict) or not isinstance(data.get("data"), str) or not data["data"]:
        return jsonify({"error": "A base64 invoice payload is required"}), 400
    requested_provider = str(data.get("ocr_provider", "")).strip().lower()
    provider_mode = requested_provider if requested_provider in {
        "auto", "gemini", "gemini-only", "mistral", "mistral-only"
    } else OCR_PROVIDER
    if provider_mode in {"mistral", "mistral-only"} and not MISTRAL_KEYS:
        return jsonify({"error": "Mistral is selected, but MISTRAL_API_KEY is missing on the server"}), 503
    if provider_mode in {"gemini", "gemini-only"} and not GEMINI_KEYS:
        return jsonify({"error": "Gemini is selected, but GEMINI_API_KEY(S) are missing on the server"}), 503
    if GEMINI_KEYS and genai is None and not MISTRAL_KEYS:
        return jsonify({"error": "google-generativeai is not installed on the server"}), 503
    if provider_mode in {"gemini", "gemini-only"} and genai is None:
        return jsonify({"error": "google-generativeai is not installed on the server"}), 503

    mime_type = data.get("mime_type")
    if mime_type not in {"image/jpeg", "image/png", "application/pdf"}:
        mime_type = "application/pdf" if data["data"].startswith("JVBERi0") else "image/jpeg"
    if mime_type == "application/pdf" and any(
        key in data for key in ("pdf_page_start", "pdf_page_end", "pdf_side")
    ):
        try:
            selected_pdf = select_pdf_pages(
                _decode_invoice_payload(data),
                int(data.get("pdf_page_start", 1)),
                int(data.get("pdf_page_end", data.get("pdf_page_start", 1))),
                str(data.get("pdf_side", "full")),
            )
            data["data"] = base64.b64encode(selected_pdf).decode("ascii")
        except (ValueError, RuntimeError) as error:
            return jsonify({"error": str(error)}), 400
    prompt = build_ocr_prompt(
        str(data.get("supplier_code", "")).strip(),
        str(data.get("column_hint", "")).strip(),
    )
    app.logger.info(
        "OCR request: provider=%s, supplier=%s",
        provider_mode,
        str(data.get("supplier_code", "")).strip() or "auto-detect",
    )

    errors: list[str] = []
    quota_failures = 0
    gemini_keys_for_request = (
        () if provider_mode in {"mistral", "mistral-only"} else GEMINI_KEYS
    )
    for key_index, key in enumerate(gemini_keys_for_request, start=1):
        try:
            model = choose_model(key)
            response = model.generate_content(
                [prompt, {"mime_type": mime_type, "data": data["data"]}],
                generation_config={
                    "temperature": 0.0,
                    "top_p": 0.1,
                    "max_output_tokens": 8192,
                },
            )
            payload = clean_json_response(response.text)
            pharma_margin_candidates: list[dict[str, Any]] = []
            if str(data.get("supplier_code", "")).strip() == "38":
                pharma_margin_candidates.append(json.loads(json.dumps(payload)))

            # A Tabark column swap is easy to spot after the first pass, but not
            # reliably fixable from numbers alone. Ask Gemini to look at the same
            # image again with the exact failure mode called out. If the retry
            # fails, keep the valid first response rather than failing the invoice.
            if _needs_tabark_price_review(payload, str(data.get("supplier_code", "")).strip()):
                try:
                    correction = model.generate_content(
                        [
                            build_price_review_prompt(prompt, payload),
                            {"mime_type": mime_type, "data": data["data"]},
                        ],
                        generation_config={
                            "temperature": 0.0,
                            "top_p": 0.1,
                            "max_output_tokens": 8192,
                        },
                    )
                    corrected_payload = clean_json_response(correction.text)
                    if corrected_payload.get("items"):
                        pharma_margin_candidates.append(
                            json.loads(json.dumps(corrected_payload))
                        )
                        payload = corrected_payload
                    app.logger.info("OCR second-pass price-column review completed")
                except Exception as correction_error:
                    app.logger.warning(
                        "OCR second-pass price review skipped: %s",
                        type(correction_error).__name__,
                    )
            elif ENABLE_PHARMA_EXTRA_OCR_PASSES and _needs_pharma_column_review(
                payload, str(data.get("supplier_code", "")).strip()
            ):
                # Pharma Overseas has several adjacent narrow numeric columns.
                # If the row total/VAT reconciliation disagrees with the draft,
                # spend one focused pass fixing the pharmacist price and margins
                # before the payload reaches the Android calculation rule.
                try:
                    correction = model.generate_content(
                        [
                            build_pharma_column_review_prompt(prompt, payload),
                            {"mime_type": mime_type, "data": data["data"]},
                        ],
                        generation_config={
                            "temperature": 0.0,
                            "top_p": 0.1,
                            "max_output_tokens": 8192,
                        },
                    )
                    corrected_payload = clean_json_response(correction.text)
                    if corrected_payload.get("items"):
                        pharma_margin_candidates.append(
                            json.loads(json.dumps(corrected_payload))
                        )
                        payload = corrected_payload
                    app.logger.info("OCR second-pass Pharma column review completed")
                except Exception as correction_error:
                    app.logger.warning(
                        "OCR second-pass Pharma column review skipped: %s",
                        type(correction_error).__name__,
                    )
            elif ENABLE_PHARMA_EXTRA_OCR_PASSES and _needs_pharma_decimal_review(
                payload, str(data.get("supplier_code", "")).strip()
            ):
                # Pharma Overseas occasionally prints the final decimal digit
                # on a line beneath the narrow price cell. Retry only when the
                # first pass contains a one-decimal value, keeping normal
                # invoices on the faster single-pass path.
                try:
                    correction = model.generate_content(
                        [
                            build_pharma_decimal_review_prompt(prompt, payload),
                            {"mime_type": mime_type, "data": data["data"]},
                        ],
                        generation_config={
                            "temperature": 0.0,
                            "top_p": 0.1,
                            "max_output_tokens": 8192,
                        },
                    )
                    corrected_payload = clean_json_response(correction.text)
                    if corrected_payload.get("items"):
                        pharma_margin_candidates.append(
                            json.loads(json.dumps(corrected_payload))
                        )
                        payload = corrected_payload
                    app.logger.info("OCR second-pass Pharma decimal review completed")
                except Exception as correction_error:
                    app.logger.warning(
                        "OCR second-pass Pharma decimal review skipped: %s",
                        type(correction_error).__name__,
                    )
            payload = repair_pharma_payload(
                payload, str(data.get("supplier_code", "")).strip()
            )
            # Pharma's margin, discount, VAT and pharmacist-price columns are
            # narrow and adjacent. Always make one final focused visual pass,
            # then copy back only those risky numeric cells. Product identity,
            # quantities and totals from the main extraction cannot be changed.
            if (
                ENABLE_PHARMA_EXTRA_OCR_PASSES
                and str(data.get("supplier_code", "")).strip() == "38"
            ):
                try:
                    supplied_crop = data.get("pharma_columns_data")
                    pharma_crop = (
                        base64.b64decode(supplied_crop)
                        if isinstance(supplied_crop, str) and supplied_crop
                        else build_pharma_columns_crop(data["data"], mime_type)
                    )
                    review_parts: list[Any] = [
                        build_pharma_values_review_prompt(prompt, payload),
                        {"mime_type": mime_type, "data": data["data"]},
                    ]
                    if pharma_crop:
                        review_parts.append(
                            {
                                "mime_type": "image/jpeg",
                                "data": base64.b64encode(pharma_crop).decode("ascii"),
                            }
                        )
                    supplied_margins = data.get("pharma_margins_data")
                    if isinstance(supplied_margins, str) and supplied_margins:
                        review_parts.append(
                            {
                                "mime_type": "image/jpeg",
                                "data": supplied_margins,
                            }
                        )
                    values_correction = model.generate_content(
                        review_parts,
                        generation_config={
                            "temperature": 0.0,
                            "top_p": 0.1,
                            "max_output_tokens": 8192,
                        },
                    )
                    reviewed_payload = clean_json_response(values_correction.text)
                    pharma_margin_candidates.append(
                        json.loads(json.dumps(reviewed_payload))
                    )
                    original_items = payload.get("items")
                    reviewed_items = reviewed_payload.get("items")
                    if (
                        isinstance(original_items, list)
                        and isinstance(reviewed_items, list)
                        and len(original_items) == len(reviewed_items)
                    ):
                        reviewed_fields = (
                            "discount_percent",
                            "pharmacist_price",
                            "pharmacist_margin",
                            "distributor_margin",
                            "tax_per_item",
                            "tax_total",
                        )
                        for original_item, reviewed_item in zip(
                            original_items, reviewed_items
                        ):
                            if not isinstance(original_item, dict) or not isinstance(
                                reviewed_item, dict
                            ):
                                continue
                            for field in reviewed_fields:
                                if field in reviewed_item:
                                    original_item[field] = reviewed_item[field]
                        app.logger.info(
                            "OCR final Pharma numeric-column review completed"
                        )
                    else:
                        app.logger.warning(
                            "OCR final Pharma review ignored: item count changed"
                        )
                except Exception as correction_error:
                    app.logger.warning(
                        "OCR final Pharma numeric review skipped: %s",
                        type(correction_error).__name__,
                    )
            payload = merge_pharma_margin_candidates(
                payload,
                pharma_margin_candidates,
                str(data.get("supplier_code", "")).strip(),
            )
            # Tabark layouts place balance figures close to the invoice
            # summary. Always reread صافي الفاتورة independently, then copy
            # only that field back so an OCR correction cannot alter good rows.
            if str(data.get("supplier_code", "")).strip() == "218":
                try:
                    total_correction = model.generate_content(
                        [
                            build_tabark_total_review_prompt(prompt, payload),
                            {"mime_type": mime_type, "data": data["data"]},
                        ],
                        generation_config={
                            "temperature": 0.0,
                            "top_p": 0.1,
                            "max_output_tokens": 8192,
                        },
                    )
                    total_payload = clean_json_response(total_correction.text)
                    reviewed_total = _as_payload_number(
                        total_payload.get("invoice_total_as_printed")
                    )
                    if reviewed_total > 0:
                        payload["invoice_total_as_printed"] = reviewed_total
                    app.logger.info("OCR second-pass Tabark net-total review completed")
                except Exception as correction_error:
                    app.logger.warning(
                        "OCR second-pass Tabark total review skipped: %s",
                        type(correction_error).__name__,
                    )
            payload = repair_distributor_invoice_total(
                payload, str(data.get("supplier_code", "")).strip()
            )
            app.logger.info("OCR completed with Gemini key %d (requested provider=%s)", key_index, provider_mode)
            response = jsonify(payload)
            response.headers["X-OCR-Provider"] = "gemini"
            return response
        except Exception as error:  # Try a rotated key without exposing the invoice.
            error_name = type(error).__name__
            errors.append(error_name)
            if _is_gemini_quota_error(error):
                quota_failures += 1
                app.logger.warning(
                    "Gemini quota/rate limit exhausted for configured key %d; trying the next key",
                    key_index,
                )

    # Gemini is the primary provider. If all of its configured keys are
    # exhausted or unavailable, use Mistral OCR without changing the Android
    # contract: it must return the same invoice JSON shape.
    mistral_errors: list[str] = []
    mistral_keys_for_request = (
        () if provider_mode in {"gemini", "gemini-only"} else MISTRAL_KEYS
    )
    for key_index, key in enumerate(mistral_keys_for_request, start=1):
        try:
            payload = call_mistral_ocr(
                api_key=key,
                encoded_data=data["data"],
                mime_type=mime_type,
                prompt=prompt,
            )
            pharma_margin_candidates = []
            if str(data.get("supplier_code", "")).strip() == "38":
                pharma_margin_candidates.append(json.loads(json.dumps(payload)))
            payload = repair_pharma_payload(
                payload, str(data.get("supplier_code", "")).strip()
            )
            if (
                ENABLE_PHARMA_EXTRA_OCR_PASSES
                and str(data.get("supplier_code", "")).strip() == "38"
            ):
                try:
                    reviewed_payload = call_mistral_ocr(
                        api_key=key,
                        encoded_data=data["data"],
                        mime_type=mime_type,
                        prompt=build_pharma_values_review_prompt(prompt, payload),
                    )
                    pharma_margin_candidates.append(
                        json.loads(json.dumps(reviewed_payload))
                    )
                    original_items = payload.get("items")
                    reviewed_items = reviewed_payload.get("items")
                    if (
                        isinstance(original_items, list)
                        and isinstance(reviewed_items, list)
                        and len(original_items) == len(reviewed_items)
                    ):
                        for original_item, reviewed_item in zip(
                            original_items, reviewed_items
                        ):
                            if not isinstance(original_item, dict) or not isinstance(
                                reviewed_item, dict
                            ):
                                continue
                            for field in (
                                "discount_percent",
                                "pharmacist_price",
                                "pharmacist_margin",
                                "distributor_margin",
                                "tax_per_item",
                                "tax_total",
                            ):
                                if field in reviewed_item:
                                    original_item[field] = reviewed_item[field]
                        app.logger.info(
                            "OCR final Mistral Pharma numeric-column review completed"
                        )
                except Exception as correction_error:
                    app.logger.warning(
                        "OCR final Mistral Pharma review skipped: %s",
                        type(correction_error).__name__,
                    )
            payload = merge_pharma_margin_candidates(
                payload,
                pharma_margin_candidates,
                str(data.get("supplier_code", "")).strip(),
            )
            payload = repair_distributor_invoice_total(
                payload, str(data.get("supplier_code", "")).strip()
            )
            app.logger.info(
                "OCR completed with Mistral key %d (requested provider=%s)",
                key_index,
                provider_mode,
            )
            response = jsonify(payload)
            response.headers["X-OCR-Provider"] = "mistral"
            return response
        except Exception as error:
            error_name = type(error).__name__
            mistral_errors.append(error_name)
            app.logger.warning(
                "Mistral OCR fallback failed for configured key %d: %s",
                key_index,
                error_name,
            )

    app.logger.error("Gemini invoice extraction failed: %s", ", ".join(errors))
    if quota_failures == len(gemini_keys_for_request) and gemini_keys_for_request and not mistral_errors:
        return jsonify(
            {
                "error": (
                    "Gemini quota exhausted or rate-limited. Add another key in "
                    "GEMINI_API_KEYS or wait for the quota window to reset."
                )
            }
        ), 429, {"Retry-After": "60"}
    if mistral_keys_for_request:
        app.logger.error("Mistral OCR fallback failed: %s", ", ".join(mistral_errors))
    return jsonify({"error": "OCR processing failed. Please retry."}), 502


@app.post("/invoice")
def save_invoice() -> tuple[Any, int] | Any:
    auth_error = require_token()
    if auth_error:
        return auth_error
    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        return jsonify({"error": "Invoice JSON is required"}), 400

    try:
        supplier_code, invoice_number, items = normalise_invoice(data)
        if not ROBOT_PATH.is_file():
            raise FileNotFoundError(f"Automation script was not found: {ROBOT_PATH}")
        report = write_robot_files(supplier_code, invoice_number, items)
        os.startfile(str(ROBOT_PATH), arguments="--run")
        changed_count = sum(
            1
            for line in report.splitlines()
            if line.startswith(("↑ ", "↓ ", "• "))
        )
        return jsonify(
            {
                "status": "ready_for_review",
                "message": "Invoice entered for review; E-PLUS will not save automatically.",
                "price_changes": changed_count,
            }
        )
    except (ValueError, FileNotFoundError) as error:
        return jsonify({"error": str(error)}), 400
    except OSError:
        app.logger.exception("Could not start the E-PLUS automation")
        return jsonify({"error": "Could not start the E-PLUS automation"}), 500


if __name__ == "__main__":
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    print(
        "OCR providers: "
        f"Gemini keys={len(GEMINI_KEYS)}, "
        f"Mistral keys={len(MISTRAL_KEYS)}, "
        f"mode={OCR_PROVIDER or 'auto'}"
    )
    print(f"Pharmacy automation server: http://{get_ip()}:8080")
    app.run(host="0.0.0.0", port=8080, debug=False)
