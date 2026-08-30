from types import SimpleNamespace
from unittest import TestCase, main
from unittest.mock import Mock, patch

import server


class TrainingOcrEndpointTest(TestCase):
    def test_training_extracts_names_without_writing_or_launching_robot(self):
        model = Mock()
        model.generate_content.return_value = SimpleNamespace(
            text='{"item_names":["Trilepsy 500 XR","Trilepsy 500 XR","Panadol"]}'
        )

        with (
            patch.object(server, "GEMINI_KEYS", ("test-key",)),
            patch.object(server, "MISTRAL_KEYS", ()),
            patch.object(server, "choose_model", return_value=model),
            patch.object(server, "atomic_write_text") as write_text,
            patch.object(server, "atomic_write_json") as write_json,
            patch.object(server.os, "startfile", create=True) as start_robot,
        ):
            response = server.app.test_client().post(
                "/training-ocr",
                json={
                    "data": "dGVzdA==",
                    "mime_type": "image/jpeg",
                    "supplier_code": "29",
                    "ocr_provider": "gemini",
                },
            )

        self.assertEqual(200, response.status_code)
        self.assertEqual(
            ["Trilepsy 500 XR", "Panadol"],
            response.get_json()["item_names"],
        )
        write_text.assert_not_called()
        write_json.assert_not_called()
        start_robot.assert_not_called()


if __name__ == "__main__":
    main()
