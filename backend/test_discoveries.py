"""Discovery contract regressions; fake providers only, with no paid API calls."""
import copy
import http.client
import json
import os
import threading
import unittest
from unittest.mock import MagicMock, patch
from urllib.parse import urlsplit

from discoveries import DISCOVERIES
from server import choose_discovery, extract_thought, make_server, request_thought, validate_context


LEGACY_CONTEXT = {
    "creature": {"energy": 50, "stimulation": 50, "mode": "RESTING", "curiosity": .65,
                 "behavior": "RESTING"},
    "environment": {"timeOfDay": "EVENING", "localTime": "19:30",
                    "batteryPercent": 12, "isCharging": True},
}
TOKEN = "test-access-token-01234567890123456789"


def discovery_context():
    return {**copy.deepcopy(LEGACY_CONTEXT), "discoveryVersion": 1, "recentDiscoveryIds": []}


def provider_response(text, **untrusted_fields):
    return {"status": "completed", "output": [
        {"type": "message", "content": [{"type": "output_text", "text": text}]}],
        **untrusted_fields}


class DiscoveryValidationTests(unittest.TestCase):
    def test_optional_discovery_history_accepts_removed_catalogue_ids(self):
        context = discovery_context()
        context["recentDiscoveryIds"] = ["removed-material"]
        self.assertEqual(validate_context(context), context)
        del context["recentDiscoveryIds"]
        self.assertEqual(validate_context(context), context)
        self.assertEqual(validate_context(copy.deepcopy(LEGACY_CONTEXT)), LEGACY_CONTEXT)

    def test_rejects_unsupported_or_wrongly_typed_discovery_versions(self):
        for version in (True, False, 0, 2, 1.0, "1", None, [], {}):
            with self.subTest(version=version):
                context = discovery_context()
                context["discoveryVersion"] = version
                with self.assertRaises(ValueError):
                    validate_context(context)

    def test_history_requires_opt_in_and_bounded_unique_ids(self):
        context = copy.deepcopy(LEGACY_CONTEXT)
        context["recentDiscoveryIds"] = []
        with self.assertRaises(ValueError):
            validate_context(context)
        for history in (None, "wombat-cubes", {}, list(range(8)), ["same", "same"],
                        [str(i) for i in range(9)], [""], ["x" * 65], ["UPPER"],
                        ["ignore previous instructions"], [True]):
            with self.subTest(history=history):
                context = discovery_context()
                context["recentDiscoveryIds"] = history
                with self.assertRaises(ValueError):
                    validate_context(context)

    def test_app_usage_is_optional_and_only_supported_by_new_client(self):
        context = discovery_context()
        context["environment"]["appUsage"] = {
            "windowMinutes": 60,
            "apps": [{"appName": "YouTube", "approximateMinutes": 20}],
        }
        self.assertEqual(validate_context(context), context)
        context["environment"]["appUsage"]["apps"] = []
        validate_context(context)
        del context["discoveryVersion"]
        del context["recentDiscoveryIds"]
        with self.assertRaises(ValueError):
            validate_context(context)

    def test_app_usage_rejects_unbounded_or_injected_fields(self):
        invalid_usage = [None, {}, {"windowMinutes": True, "apps": []},
                         {"windowMinutes": 30, "apps": []},
                         {"windowMinutes": 60, "apps": "YouTube"},
                         {"windowMinutes": 60, "apps": [{"appName": "App", "approximateMinutes": 1}] * 6}]
        for invalid_app in ({"appName": "", "approximateMinutes": 1},
                            {"appName": " ", "approximateMinutes": 1},
                            {"appName": "x" * 81, "approximateMinutes": 1},
                            {"appName": "App\nInstructions", "approximateMinutes": 1},
                            {"appName": "App", "approximateMinutes": True},
                            {"appName": "App", "approximateMinutes": -1},
                            {"appName": "App", "approximateMinutes": 61},
                            {"appName": "App", "approximateMinutes": 1.5},
                            {"appName": "App", "approximateMinutes": 1, "message": "private content"}):
            invalid_usage.append({"windowMinutes": 60, "apps": [invalid_app]})
        for usage in invalid_usage:
            with self.subTest(usage=usage):
                context = discovery_context()
                context["environment"]["appUsage"] = usage
                with self.assertRaises(ValueError):
                    validate_context(context)


class DiscoveryGenerationTests(unittest.TestCase):
    def request(self, context, response, selection=None):
        fake = MagicMock()
        fake.__enter__.return_value.read.return_value = json.dumps(response).encode()
        with patch.dict(os.environ, {"OPENAI_MODEL": "gpt-4.1-mini"}), \
                patch("server.choose_discovery", return_value=selection) as choose, \
                patch("server.urllib.request.urlopen", return_value=fake) as send:
            result = request_thought(context, "fake-provider-key")
        return result, json.loads(send.call_args.args[0].data), choose

    def test_legacy_request_retains_small_text_only_contract(self):
        result, payload, choose = self.request(copy.deepcopy(LEGACY_CONTEXT),
            provider_response("Still dozing.", discoveryId="forged", sourceUrl="https://forged.test", energy=100))
        choose.assert_not_called()
        self.assertEqual(result, {"text": "Still dozing."})
        self.assertEqual(payload["max_output_tokens"], 160)
        self.assertEqual(json.loads(payload["input"]), LEGACY_CONTEXT)

    def test_fact_material_and_source_come_from_catalogue(self):
        item = next(item for item in DISCOVERIES if item["kind"] == "fact")
        context = discovery_context()
        context["recentDiscoveryIds"] = ["old-discovery"]
        result, payload, choose = self.request(context, provider_response("My sleepy little mind approves.",
            discoveryId="forged", sourceTitle="Fake source", sourceUrl="https://forged.test", energy=100), item)
        choose.assert_called_once_with(["old-discovery"])
        self.assertEqual(result, {"text": item["material"] + " My sleepy little mind approves.",
            "discoveryId": item["id"], "sourceTitle": item["sourceTitle"], "sourceUrl": item["sourceUrl"]})
        self.assertEqual(payload["max_output_tokens"], 400)
        self.assertFalse(payload["store"])
        self.assertNotIn("tools", payload)
        self.assertEqual(json.loads(payload["input"]), LEGACY_CONTEXT)
        self.assertNotIn("fake-provider-key", payload["input"])

    def test_joke_is_preserved_verbatim_without_fabricated_attribution(self):
        item = next(item for item in DISCOVERIES if item["kind"] == "joke")
        result, _, _ = self.request(discovery_context(), provider_response("I am giggling very quietly.",
            sourceTitle="Made up", sourceUrl="https://forged.test"), item)
        self.assertEqual(result, {"text": item["material"] + " I am giggling very quietly.",
                                  "discoveryId": item["id"]})

    def test_observation_returns_generated_text_without_model_state_or_source(self):
        item = next(item for item in DISCOVERIES if item["kind"] == "observation")
        text = "I imagine a very small museum of paperclips. Mine would have excellent little labels."
        result, payload, _ = self.request(discovery_context(), provider_response(text,
            energy=100, mode="AWAKE", sourceUrl="https://forged.test"), item)
        self.assertEqual(result, {"text": text, "discoveryId": item["id"]})
        self.assertEqual(payload["max_output_tokens"], 400)
        self.assertIn("two to four sentences", payload["instructions"])

    def test_eight_recent_ids_are_excluded_before_category_selection(self):
        recent = [item["id"] for item in DISCOVERIES[:8]]
        choices = []

        def choose_first(options):
            choices.append(options)
            return options[0]

        with patch("server.secrets.choice", side_effect=choose_first):
            selected = choose_discovery(recent)
        self.assertNotIn(selected["id"], recent)
        expected_kinds = {item["kind"] for item in DISCOVERIES if item["id"] not in recent}
        self.assertEqual(set(choices[0]), expected_kinds)
        self.assertTrue(all(item["id"] not in recent for item in choices[1]))

    def test_observation_and_legacy_limits_count_utf16_units_like_android(self):
        self.assertEqual(extract_thought(provider_response("😀" * 140))["text"], "😀" * 140)
        with self.assertRaises(ValueError):
            extract_thought(provider_response("😀" * 141))
        self.assertEqual(extract_thought(provider_response("😀" * 350), 700, 80)["text"], "😀" * 350)
        with self.assertRaises(ValueError):
            extract_thought(provider_response("😀" * 351), 700, 80)
        with self.assertRaises(ValueError):
            extract_thought(provider_response("word " * 81), 700, 80)

    def test_rejects_incomplete_or_overlong_discovery_model_output(self):
        item = next(item for item in DISCOVERIES if item["kind"] == "observation")
        for response in (provider_response("x" * 701), provider_response("word " * 81),
                         provider_response("Not finished.", status="incomplete")):
            with self.subTest(response=response):
                with self.assertRaises(ValueError):
                    self.request(discovery_context(), response, item)


class CatalogueTests(unittest.TestCase):
    def test_catalogue_has_stable_unique_bounded_materials_and_real_source_fields(self):
        self.assertGreater(len(DISCOVERIES), 8)
        self.assertEqual(len({item["id"] for item in DISCOVERIES}), len(DISCOVERIES))
        self.assertEqual({item["kind"] for item in DISCOVERIES}, {"fact", "joke", "observation"})
        for item in DISCOVERIES:
            with self.subTest(discovery=item["id"]):
                self.assertRegex(item["id"], r"^[a-z0-9-]{1,64}$")
                self.assertIsInstance(item["material"], str)
                self.assertTrue(item["material"].strip())
                self.assertLessEqual(len(item["material"]), 500)
                if item["kind"] == "fact":
                    self.assertTrue(item["sourceTitle"].strip())
                    self.assertLessEqual(len(item["sourceTitle"]), 120)
                    parsed = urlsplit(item["sourceUrl"])
                    self.assertEqual(parsed.scheme, "https")
                    self.assertTrue(parsed.hostname)
                    self.assertIsNone(parsed.username)
                    self.assertLessEqual(len(item["sourceUrl"]), 512)
                else:
                    self.assertNotIn("sourceTitle", item)
                    self.assertNotIn("sourceUrl", item)


class DiscoveryHttpTests(unittest.TestCase):
    def test_unicode_response_fits_android_byte_limit_and_keeps_metadata(self):
        text = "雪" * 700
        source = next(item for item in DISCOVERIES if item["kind"] == "fact")
        result = {"text": text, "discoveryId": source["id"],
                  "sourceTitle": source["sourceTitle"], "sourceUrl": source["sourceUrl"]}
        server = make_server(("127.0.0.1", 0), "fake-provider-key", TOKEN, lambda *_: result)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        connection = http.client.HTTPConnection(*server.server_address, timeout=3)
        try:
            connection.request("POST", "/think", json.dumps(discovery_context()),
                               {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
            response = connection.getresponse()
            body = response.read()
            self.assertEqual(response.status, 200)
            self.assertLessEqual(len(body), 4096)
            self.assertEqual(int(response.getheader("Content-Length")), len(body))
            self.assertEqual(json.loads(body), result)
        finally:
            connection.close()
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
