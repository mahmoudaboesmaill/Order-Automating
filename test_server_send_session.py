import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import server


class SendSessionApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        runtime = Path(self.temp_dir.name)
        self.paths = {
            "RUNTIME_DIR": runtime,
            "PRICE_CACHE_PATH": runtime / "last_seen_prices.json",
            "PRICE_ALERTS_PATH": runtime / "price_alerts.txt",
            "HEADER_PATH": runtime / "final_invoice_header.tsv",
            "ITEMS_PATH": runtime / "final_invoice_items.tsv",
            "INVOICE_PATH": runtime / "final_invoice.json",
            "SEND_SESSION_PATH": runtime / "send_session.json",
            "ROBOT_CONTROL_PATH": runtime / "robot_control.ini",
            "ROBOT_CHECKPOINT_PATH": runtime / "robot_checkpoint.state",
            "ROBOT_PATH": runtime / "OrderRobot.ahk",
        }
        self.patchers = [patch.object(server, name, value) for name, value in self.paths.items()]
        for patcher in self.patchers:
            patcher.start()
        self.paths["ROBOT_PATH"].write_text("#Requires AutoHotkey v2.0\n", encoding="utf-8")
        self.startfile = patch.object(server.os, "startfile", create=True)
        self.startfile_mock = self.startfile.start()
        self.client = server.app.test_client()

    def tearDown(self) -> None:
        self.startfile.stop()
        for patcher in reversed(self.patchers):
            patcher.stop()
        self.temp_dir.cleanup()

    @staticmethod
    def invoice_payload() -> dict:
        return {
            "supplier_code": "29",
            "invoice_number": "INV-100",
            "items": [
                {
                    "itm_code": "1001",
                    "quantity": 2,
                    "bonus": 0,
                    "taxes": 0,
                    "price": 10.5,
                    "sale_price": 12,
                    "expiry_month": "06",
                    "expiry_year": "28",
                },
                {
                    "itm_code": "1002",
                    "quantity": 1,
                    "bonus": 0,
                    "taxes": 0,
                    "price": 20,
                    "sale_price": 24,
                    "expiry_month": "09",
                    "expiry_year": "28",
                },
            ],
        }

    def checkpoint(self, **changes: object) -> dict:
        session = json.loads(self.paths["SEND_SESSION_PATH"].read_text(encoding="utf-8"))
        values = {
            "job_id": session["job_id"],
            "status": "interrupted",
            "phase": "item_completed",
            "header_entered": True,
            "next_index": 1,
            "current_index": -1,
            "total_items": 2,
            "window_id": 12345,
            "pid": 0,
        }
        values.update(changes)
        server._write_robot_checkpoint(**values)
        return session

    def test_second_invoice_is_blocked_while_first_session_is_active(self) -> None:
        first = self.client.post("/invoice", json=self.invoice_payload())
        second = self.client.post("/invoice", json=self.invoice_payload())

        self.assertEqual(first.status_code, 200)
        self.assertEqual(second.status_code, 409)
        self.assertIn("غير مكتمل", second.get_json()["error"])
        self.startfile_mock.assert_called_once()

    def test_resume_starts_at_next_item_and_skips_header(self) -> None:
        self.client.post("/invoice", json=self.invoice_payload())
        self.checkpoint()

        response = self.client.post("/invoice/session/resume", json={})

        self.assertEqual(response.status_code, 200)
        control = self.paths["ROBOT_CONTROL_PATH"].read_text(encoding="utf-8")
        self.assertIn("mode=resume", control)
        self.assertIn("start_index=1", control)
        self.assertIn("skip_header=1", control)
        self.assertEqual(self.startfile_mock.call_count, 2)

    def test_ambiguous_item_requires_explicit_resolution(self) -> None:
        self.client.post("/invoice", json=self.invoice_payload())
        self.checkpoint(phase="item_in_progress", next_index=0, current_index=0)

        blocked = self.client.post("/invoice/session/resume", json={})
        resumed = self.client.post(
            "/invoice/session/resume", json={"resolution": "completed"}
        )

        self.assertEqual(blocked.status_code, 409)
        self.assertTrue(blocked.get_json()["session"]["requires_resolution"])
        self.assertEqual(resumed.status_code, 200)
        control = self.paths["ROBOT_CONTROL_PATH"].read_text(encoding="utf-8")
        self.assertIn("start_index=1", control)

    def test_cancelled_dead_session_releases_busy_lock(self) -> None:
        self.client.post("/invoice", json=self.invoice_payload())
        self.checkpoint()

        cancelled = self.client.post("/invoice/session/cancel", json={})
        next_invoice = self.client.post("/invoice", json=self.invoice_payload())

        self.assertEqual(cancelled.status_code, 200)
        self.assertEqual(cancelled.get_json()["status"], "cancelled")
        self.assertEqual(next_invoice.status_code, 200)


if __name__ == "__main__":
    unittest.main()
