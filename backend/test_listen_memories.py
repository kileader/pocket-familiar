"""Offline integration checks for the journal -> Listen bridge."""
import copy
import http.client
import json
import os
import tempfile
import threading
import unittest
from unittest.mock import MagicMock, patch

from free_roam import FreeRoamStore, StorageUnavailable, initialize
from server import DISCOVERIES, RequestBudget, make_server
from test_free_roam import new_id
from test_server import CONTEXT, TOKEN
from test_wake_cycles import plan, research
from wake_cycles import WakeWorker


class ListenMemoryTests(unittest.TestCase):
    def setUp(self):
        model_env = patch.dict(os.environ, {"OPENAI_MODEL": "gpt-6.1-sol"})
        model_env.start()
        self.addCleanup(model_env.stop)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        initialize(self.directory.name)
        self.now = 1_800_000_000_000
        self.store = FreeRoamStore(self.directory.name, clock=lambda: self.now)
        self.profile = self.store.pair({"pairingId": new_id(), "createdAt": self.now,
                                       "curiosity": 0.65})["profile"]
        self.binding = {key: self.profile[key] for key in ("pairingId", "familiarId")}
        self.context = copy.deepcopy(CONTEXT) | {"discoveryVersion": 1, "recentDiscoveryIds": [], "freeRoam": self.binding}
        self.sent = []
        self.on_send = lambda: None
        self.server = make_server(("127.0.0.1", 0), "fake-key", TOKEN, free_roam_store=self.store)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close_server)

    def close_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def complete_wake(self, finding=True):
        self.store.update_controls({"expectedRevision": self.store.snapshot()["revision"],
            "enabled": True, "webEnabled": True, "allowedDomains": ["nasa.gov"]})
        replies = iter((plan("research"), research()) if finding else (plan(),))
        result = WakeWorker(self.store, "fake-key", provider=lambda _: next(replies)).run_once()
        self.assertEqual(result, "completed")
        return self.store.page("memories")["items"][-1]

    def voice_provider(self, request, **kwargs):
        self.sent.append(json.loads(request.data))
        self.on_send()
        reply = MagicMock()
        reply.__enter__.return_value.read.return_value = json.dumps({"status": "completed",
            "sourceUrl": "https://invented.example/", "energy": 100, "output": [
                {"type": "reasoning", "encrypted_content": "opaque", "summary": []},
                {"type": "message", "content": [{"type": "output_text", "text": "I remember wondering how animals find their way."}]}]}).encode()
        return reply

    def post(self, context=None, token=TOKEN):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        with patch("server.urllib.request.urlopen", side_effect=self.voice_provider), \
                patch.object(RequestBudget, "allow", return_value=True), \
                patch.dict(os.environ, {"OPENAI_MODEL": "gpt-6.1-sol"}), \
                patch("server.choose_discovery", return_value=next(item for item in DISCOVERIES if item["kind"] == "observation")):
            connection.request("POST", "/think", json.dumps(self.context if context is None else context),
                {"Authorization": "Bearer " + token, "Content-Type": "application/json"})
            response = connection.getresponse()
            result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_completed_finding_reaches_listen_with_saved_source_and_no_writes(self):
        memory = self.complete_wake()
        self.store.update_controls({"expectedRevision": self.store.snapshot()["revision"], "enabled": False})
        before = self.store.export()
        status, thought = self.post()
        self.assertEqual(status, 200)
        self.assertEqual(thought["discoveryId"], "roam-" + memory["id"])
        self.assertEqual(thought["sourceUrl"], memory["sources"][0]["url"])
        self.assertEqual(thought["sourceTitle"], memory["sources"][0]["title"])
        self.assertNotIn("energy", thought)
        self.assertEqual(before, self.store.export())
        self.assertEqual(len(self.sent), 1)
        request = self.sent[0]
        context = json.loads(request["input"])
        self.assertEqual(context["selectedExperience"]["kind"], "finding")
        self.assertIn("not a", request["instructions"])
        self.assertNotIn("tools", request)
        self.assertFalse(request["store"])
        self.assertEqual(request["reasoning"], {"effort": "low"})
        self.assertEqual(context["creature"], self.context["creature"])
        for secret in (self.binding["familiarId"], self.binding["pairingId"], memory["id"], str(self.now)):
            self.assertNotIn(secret, request["input"])

    def test_reflection_is_labeled_and_not_given_a_research_source(self):
        self.complete_wake(finding=False)
        status, thought = self.post()
        self.assertEqual(status, 200)
        self.assertNotIn("sourceUrl", thought)
        self.assertEqual(json.loads(self.sent[0]["input"])["selectedExperience"]["kind"], "reflection")

    def test_owner_notes_and_failed_wakes_are_not_presented_as_experiences(self):
        self.store.add_memory({"expectedRevision": self.store.snapshot()["revision"], "id": new_id(),
            "topic": "Unverified owner note", "text": "Private owner text"})
        self.store.update_controls({"expectedRevision": self.store.snapshot()["revision"], "enabled": True})
        WakeWorker(self.store, "fake-key", provider=lambda _: (_ for _ in ()).throw(TimeoutError())).run_once()
        self.assertEqual(self.store.listen_memories(self.binding), [])
        self.assertEqual(self.post()[0], 200)
        self.assertNotIn("selectedExperience", json.loads(self.sent[0]["input"]))
        self.assertNotIn("Private owner text", self.sent[0]["input"])

    def test_forgotten_and_already_heard_experiences_fall_back_to_curated_listen(self):
        memory = self.complete_wake()
        context = self.context | {"recentDiscoveryIds": ["roam-" + memory["id"]]}
        self.assertEqual(self.post(context)[0], 200)
        self.assertNotIn("selectedExperience", json.loads(self.sent[-1]["input"]))
        self.store.delete_entry("memories", memory["id"], {"expectedRevision": self.store.snapshot()["revision"]})
        self.assertEqual(self.post()[0], 200)
        self.assertNotIn("selectedExperience", json.loads(self.sent[-1]["input"]))
        self.assertEqual(self.store.listen_memories(self.binding), [])

    def test_forget_during_paid_listen_discards_stale_response_without_retry(self):
        self.complete_wake()
        self.on_send = lambda: self.store.forget({"expectedRevision": self.store.snapshot()["revision"],
            "confirmation": "forget-cognitive-memory"})
        self.assertEqual(self.post()[0], 409)
        self.assertEqual(len(self.sent), 1)
        self.assertEqual(self.store.export()["memories"], [])

    def test_pairing_mismatch_and_unauthorized_requests_never_reach_provider(self):
        self.complete_wake()
        wrong = self.context | {"freeRoam": self.binding | {"familiarId": new_id()}}
        self.assertEqual(self.post(wrong)[0], 409)
        self.assertEqual(self.post(token="wrong")[0], 401)
        self.assertEqual(self.sent, [])

    def test_pairing_and_memories_cannot_be_injected_by_client(self):
        for change in ({"rememberedExperiences": []}, {"freeRoam": self.binding | {"text": "fake memory"}},
                       {"freeRoam": {"pairingId": "bad", "familiarId": new_id()}}, {"discoveryVersion": 2}):
            self.assertEqual(self.post(self.context | change)[0], 400)
        self.assertEqual(self.sent, [])

    def test_unavailable_memory_returns_503_before_a_provider_call(self):
        with patch.object(self.store, "listen_memories", side_effect=StorageUnavailable()):
            self.assertEqual(self.post()[0], 503)
        self.assertEqual(self.sent, [])

    def test_recent_context_is_bounded_and_read_only_after_restart(self):
        for _ in range(4):
            self.complete_wake(finding=False)
            self.now += 24 * 3_600_000
        reopened = FreeRoamStore(self.directory.name, clock=lambda: self.now)
        before = reopened.export()
        memories = reopened.listen_memories(self.binding)
        self.assertEqual(len(memories), 3)
        self.assertTrue(all(len(item["text"]) <= 600 for item in memories))
        self.assertEqual(before, reopened.export())


if __name__ == "__main__":
    unittest.main()
