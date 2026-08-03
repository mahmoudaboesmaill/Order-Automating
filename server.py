import os
import time
import socket
import base64
import json
import requests
from flask import Flask, request, jsonify
import google.generativeai as genai

app = Flask(__name__)

# ✅ مفاتيحك (AQ أو AIza)
API_KEYS = [
    "AQ.Ab8RN6J8q4LZRtgwsS9cfjTzdigsdbxPYB6y_fw4CBtvRrNC8g"
]

def get_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 1))
        IP = s.getsockname()[0]
    except: IP = '127.0.0.1'
    finally: s.close()
    return IP

@app.route('/')
def home():
    return f"✅ Pharmacy Server is Online | IP: {get_ip()}:8080"

# --- 1. رابط الـ OCR (Gemini) ---
@app.route('/gemini', methods=['POST'])
def process_ocr():
    print("\n📸 New OCR Request")
    data = request.json
    img_base64 = data.get('data', '')
    if not img_base64: return jsonify({"error": "No data"}), 400

    mime_type = "application/pdf" if "JVBERi0" in img_base64[:20] else "image/jpeg"

    for key in API_KEYS:
        try:
            genai.configure(api_key=key)
            available_models = [m.name for m in genai.list_models() if 'generateContent' in m.supported_generation_methods]
            selected_model = next((m for m in available_models if "flash" in m), available_models[0])
            model = genai.GenerativeModel(selected_model)
            
            prompt = "Extract pharmacy invoice JSON. Return ONLY keys: supplier_name, invoice_number, date, invoice_total_as_printed, items[name, quantity, bonus, unit_price, discount_percent, line_total_as_printed, sale_p]"
            
            response = model.generate_content([prompt, {'mime_type': mime_type, 'data': img_base64}])
            return response.text.replace("```json", "").replace("```", "").strip(), 200, {'Content-Type': 'application/json'}
        except Exception as e:
            print(f"❌ Gemini Error: {e}")
            continue
    return jsonify({"error": "OCR Failed"}), 429

# --- 2. رابط استقبال الفاتورة النهائية وتشغيل الروبوت (حل مشكلة 404) ---
@app.route('/invoice', methods=['POST'])
def save_invoice():
    print("\n📩 Final Invoice Received for Automation")
    try:
        data = request.json
        temp_dir = os.environ.get('TEMP')
        file_path = os.path.join(temp_dir, 'final_invoice.json')
        
        # محاولة الكتابة (5 محاولات لفك القفل)
        for i in range(5):
            try:
                with open(file_path, 'w', encoding='utf-8') as f:
                    json.dump(data, f, ensure_ascii=False, indent=4)
                break
            except PermissionError:
                time.sleep(0.5)

        print(f"✅ Saved to: {file_path}")
        
        if os.path.exists("OrderRobot.ahk"):
            os.startfile("OrderRobot.ahk")
            print("🤖 Robot Triggered!")

        return jsonify({"status": "success", "message": "Saved and Triggered"}), 200
    except Exception as e:
        print(f"❌ Error: {e}")
        return jsonify({"error": str(e)}), 500

if __name__ == '__main__':
    print(f"🚀 PHARMACY SERVER RUNNING: http://{get_ip()}:8080")
    app.run(host='0.0.0.0', port=8080, debug=False)