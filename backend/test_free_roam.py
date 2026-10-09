import copy
import http.client
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from pathlib import Path
from unittest.mock import patch

from free_roam import (Conflict, DATABASE_NAME, DEFAULT_CONTROLS, FreeRoamStore,
                       StorageUnavailable, initialize, store_from_environment)
from server import make_server
from test_server import CONTEXT, TOKEN


def new_id():
    return str(uuid.uuid4())


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = initialize(self.directory.name)
        self.store = FreeRoamStore(self.directory.name, clock=lambda: 1_800_000_000_000)
        self.pairing = {"pairingId": new_id(), "createdAt": 1_790_000_000_000, "curiosity": 0.65}

    def pair(self):
        return self.store.pair(self.pairing)

    def revision(self):
        return self.store.snapshot()["revision"]

    def note(self, **changes):
        return {"expectedRevision": self.revision(), "id": new_id(), "topic": "navigation",
                "text": "An owner-supplied starting question, not a researched experience."} | changes

    def interest(self, **changes):
        return {"expectedRevision": self.revision(), "id": new_id(), "topic": "navigation", "priority": 3} | changes

    def goal(self, **changes):
        return {"expectedRevision": self.revision(), "id": new_id(), "topic": "navigation",
                "question": "How do migrating animals find their way?", "priority": 3, "status": "active"} | changes

    def test_provisioning_is_explicit_and_disabled(self):
        state = self.store.snapshot()
        self.assertIsNone(state["profile"])
        self.assertEqual(state["controls"], DEFAULT_CONTROLS)
        self.assertEqual(state["revision"], 0)
        self.assertFalse(state["runtime"]["autonomousExecutionAvailable"])
        self.assertFalse(state["runtime"]["webToolsAvailable"])
        self.assertEqual(state["memoryCount"], 0)

    def test_pairing_preserves_identity_and_retries_do_not_duplicate_audit(self):
        first = self.pair()
        self.assertEqual(self.store.pair(self.pairing), first)
        restarted = FreeRoamStore(self.directory.name)
        self.assertEqual(restarted.snapshot(), first)
        self.assertEqual(first["profile"]["createdAt"], self.pairing["createdAt"])
        self.assertEqual(first["profile"]["curiosity"], self.pairing["curiosity"])
        self.assertNotEqual(first["profile"]["familiarId"], self.pairing["pairingId"])
        self.assertEqual(len(self.store.page("audit")["items"]), 1)

    def test_pairing_cannot_replace_familiar_or_personality(self):
        first = self.pair()
        for changed in ({"pairingId": new_id()}, {"createdAt": 0}, {"curiosity": 0.9}):
            with self.subTest(changed=changed), self.assertRaises(Conflict):
                self.store.pair(self.pairing | changed)
            self.assertEqual(self.store.snapshot(), first)

    def test_pairing_rejects_body_state_private_context_and_bad_identity(self):
        invalid = ({"energy": 80}, {"environment": {"appUsage": []}}, {"interactionCount": 4},
                   {"pairingId": "1"}, {"createdAt": True}, {"createdAt": -1},
                   {"curiosity": True}, {"curiosity": float("nan")}, {"curiosity": 10**400})
        for changes in invalid:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.store.pair(self.pairing | changes)
        self.assertIsNone(self.store.snapshot()["profile"])

    def test_writes_require_pairing(self):
        operations = (
            lambda: self.store.update_controls({"expectedRevision": 0, "dailyWakeLimit": 2}),
            lambda: self.store.add_memory(self.note()),
            lambda: self.store.save_interest(self.interest()),
            lambda: self.store.save_goal(self.goal()),
        )
        for operation in operations:
            with self.assertRaises(Conflict):
                operation()
        self.assertEqual(self.revision(), 0)

    def test_controls_and_knowledge_survive_restart(self):
        self.pair()
        self.store.update_controls({"expectedRevision": self.revision(), "webEnabled": True,
                                    "allowedDomains": ["NASA.GOV"], "wakeIntervalHours": 12,
                                    "dailyWakeLimit": 2, "monthlyBudgetCents": 250})
        self.store.add_memory(self.note())
        self.store.save_interest(self.interest())
        self.store.save_goal(self.goal())
        original = self.store.export()
        restored = FreeRoamStore(self.directory.name, clock=self.store.clock).export()
        self.assertEqual(restored, original)
        self.assertEqual(restored["controls"]["allowedDomains"], ["nasa.gov"])
        self.assertFalse(restored["controls"]["enabled"])
        self.assertEqual(restored["memories"][0]["kind"], "owner_note")

    def test_current_build_cannot_enable_execution(self):
        self.pair()
        before = self.store.export()
        with self.assertRaises(Conflict):
            self.store.update_controls({"expectedRevision": self.revision(), "enabled": True})
        self.assertEqual(self.store.export(), before)

    def test_control_validation_is_atomic_and_enforces_limits(self):
        self.pair()
        before = self.store.export()
        changes = ({"webEnabled": True}, {"enabled": 1}, {"webEnabled": "true"},
                   {"wakeIntervalHours": 5}, {"wakeIntervalHours": True}, {"dailyWakeLimit": 5},
                   {"monthlyBudgetCents": -1}, {"monthlyBudgetCents": 1.5},
                   {"allowedDomains": ["https://nasa.gov"]}, {"allowedDomains": ["*.nasa.gov"]},
                   {"allowedDomains": ["127.0.0.1"]}, {"allowedDomains": ["host.internal"]},
                   {"allowedDomains": ["nasa.gov", "NASA.GOV"]}, {"allowedDomains": ["nasa.gov/path"]},
                   {"allowedDomains": ["nasa.gov:443"]}, {"allowedDomains": ["nasa.gov@evil.test"]},
                   {"model": "expensive-model"}, {"energy": 100}, {"expectedRevision": True})
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.store.update_controls({"expectedRevision": self.revision(), "dailyWakeLimit": 2} | change)
            self.assertEqual(self.store.export(), before)

    def test_stale_controls_do_not_overwrite_new_permissions(self):
        state = self.pair()
        self.store.update_controls({"expectedRevision": state["revision"], "monthlyBudgetCents": 100})
        with self.assertRaises(Conflict):
            self.store.update_controls({"expectedRevision": state["revision"], "monthlyBudgetCents": 500})
        self.assertEqual(self.store.snapshot()["controls"]["monthlyBudgetCents"], 100)

    def test_note_retries_are_idempotent_and_cannot_forge_experience(self):
        self.pair()
        request = self.note()
        first = self.store.add_memory(request)
        self.assertEqual(self.store.add_memory(request), first)
        self.assertEqual(self.store.snapshot()["memoryCount"], 1)
        self.assertEqual(len(self.store.page("audit")["items"]), 2)
        for forged in ({"kind": "experience"}, {"actor": "agent"}, {"sourceUrl": "https://nasa.gov"},
                       {"createdAt": 1}, {"verified": True}, {"expectedRevision": True}):
            with self.subTest(forged=forged), self.assertRaises(ValueError):
                self.store.add_memory(request | forged)
        with self.assertRaises(Conflict):
            self.store.add_memory(request | {"expectedRevision": self.revision(), "text": "Replacement text"})
        self.assertEqual(self.store.page("memories")["items"][0]["text"], request["text"])

    def test_memory_pages_are_bounded_and_keep_cursors_after_deletion(self):
        self.pair()
        notes = [self.store.add_memory(self.note(text=f"Owner note {number}"))["memory"] for number in range(4)]
        first = self.store.page("memories", limit=2)
        self.assertEqual([item["id"] for item in first["items"]], [item["id"] for item in notes[:2]])
        self.store.delete_entry("memories", notes[0]["id"], {"expectedRevision": self.revision()})
        second = self.store.page("memories", after=first["nextCursor"], limit=2)
        self.assertEqual([item["id"] for item in second["items"]], [item["id"] for item in notes[2:]])
        self.assertIsNone(second["nextCursor"])
        for after, limit in ((-1, 20), (True, 20), (0, 51), (0, 0)):
            with self.assertRaises(ValueError):
                self.store.page("memories", after, limit)

    def test_forgetting_a_note_removes_text_from_export_and_audit(self):
        self.pair()
        note = self.store.add_memory(self.note(text="Private owner note that must disappear"))["memory"]
        result = self.store.delete_entry("memories", note["id"], {"expectedRevision": self.revision()})
        self.assertTrue(result["deleted"])
        self.assertNotIn(note["text"], json.dumps(self.store.export()))

    def test_interests_have_unique_topics_and_a_fixed_capacity(self):
        self.pair()
        first = self.store.save_interest(self.interest())["interest"]
        with self.assertRaises(Conflict):
            self.store.save_interest(self.interest(topic="Navigation"))
        updated = self.store.save_interest(self.interest(id=first["id"], priority=5))["interest"]
        self.assertEqual(updated["createdAt"], first["createdAt"])
        for number in range(9):
            self.store.save_interest(self.interest(topic=f"Topic {number}"))
        with self.assertRaises(Conflict):
            self.store.save_interest(self.interest(topic="An eleventh topic"))
        self.assertEqual(len(self.store.snapshot()["interests"]), 10)

    def test_only_three_goals_can_be_active_and_completed_goals_remain(self):
        self.pair()
        goals = [self.store.save_goal(self.goal())["goal"] for _ in range(3)]
        with self.assertRaises(Conflict):
            self.store.save_goal(self.goal())
        self.store.save_goal(self.goal(id=goals[0]["id"], status="completed"))
        self.store.save_goal(self.goal())
        states = [goal["status"] for goal in self.store.snapshot()["goals"]]
        self.assertEqual(states.count("active"), 3)
        self.assertEqual(states.count("completed"), 1)

    def test_history_limits_reject_new_content_without_losing_existing_content(self):
        self.pair()
        self.store.add_memory(self.note())
        self.store.save_goal(self.goal(status="completed"))
        before = self.store.export()
        with patch("free_roam.MAX_MEMORIES", 1), self.assertRaises(Conflict):
            self.store.add_memory(self.note(text="A second note"))
        with patch("free_roam.MAX_GOALS", 1), self.assertRaises(Conflict):
            self.store.save_goal(self.goal(status="completed"))
        self.assertEqual(self.store.export(), before)

    def test_unbounded_or_invalid_knowledge_is_rejected_atomically(self):
        self.pair()
        before = self.store.export()
        for changes in ({"text": "x" * 2001}, {"topic": "x" * 81}, {"text": "\ud800"},
                        {"text": "Private\x00content"}, {"text": "  "}, {"id": "not-an-id"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.store.add_memory(self.note(**changes))
        for changes in ({"question": "x" * 501}, {"priority": True}, {"status": "invented"},
                        {"actor": "agent"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.store.save_goal(self.goal(**changes))
        self.assertEqual(self.store.export(), before)

    def test_concurrent_writes_across_store_instances_cannot_lose_updates(self):
        revision = self.pair()["revision"]

        def update(number):
            other = FreeRoamStore(self.directory.name)
            try:
                return other.save_interest(self.interest(expectedRevision=revision, topic=f"Topic {number}"))
            except Conflict:
                return "conflict"

        with ThreadPoolExecutor(max_workers=2) as pool:
            outcomes = list(pool.map(update, range(2)))
        self.assertEqual(outcomes.count("conflict"), 1)
        self.assertEqual(len(self.store.snapshot()["interests"]), 1)
        self.assertEqual(self.revision(), revision + 1)

    def test_forget_requires_confirmation_and_preserves_identity(self):
        profile = self.pair()["profile"]
        self.store.add_memory(self.note())
        self.store.save_interest(self.interest())
        self.store.save_goal(self.goal())
        self.store.update_controls({"expectedRevision": self.revision(), "webEnabled": True, "allowedDomains": ["nasa.gov"]})
        before = self.store.export()
        with self.assertRaises(ValueError):
            self.store.forget({"expectedRevision": self.revision(), "confirmation": "yes"})
        self.assertEqual(self.store.export(), before)
        self.store.forget({"expectedRevision": self.revision(), "confirmation": "forget-cognitive-memory"})
        result = self.store.export()
        self.assertEqual(result["profile"], profile)
        self.assertEqual(result["memories"], [])
        self.assertEqual(result["interests"], [])
        self.assertEqual(result["goals"], [])
        self.assertFalse(result["controls"]["enabled"])
        self.assertFalse(result["controls"]["webEnabled"])
        self.assertEqual([item["action"] for item in result["audit"]], ["cognitive_memory_forgotten"])

    def test_reinitializing_valid_storage_never_resets_it(self):
        self.pair()
        self.store.add_memory(self.note())
        before = self.store.export()
        self.assertEqual(initialize(self.directory.name), self.path)
        self.assertEqual(self.store.export(), before)

    def test_missing_storage_is_not_created_implicitly(self):
        with tempfile.TemporaryDirectory() as empty:
            store = FreeRoamStore(empty)
            with self.assertRaises(StorageUnavailable):
                store.snapshot()
            self.assertFalse((Path(empty) / DATABASE_NAME).exists())
            with self.assertRaises(StorageUnavailable):
                store.pair(self.pairing)
            self.assertFalse((Path(empty) / DATABASE_NAME).exists())
            with self.assertRaises(StorageUnavailable):
                store.backup(Path(empty) / "backup.sqlite3")
            self.assertFalse((Path(empty) / "backup.sqlite3").exists())

    def test_deleted_live_storage_does_not_recreate_a_familiar(self):
        self.pair()
        self.path.unlink()
        with self.assertRaises(StorageUnavailable):
            self.store.snapshot()
        self.assertFalse(self.path.exists())

    def test_unknown_schema_and_corruption_are_preserved_and_rejected(self):
        self.pair()
        with closing(sqlite3.connect(self.path)) as connection, connection:
            connection.execute("PRAGMA user_version = 99")
        for operation in (self.store.snapshot, lambda: initialize(self.directory.name)):
            with self.assertRaises(StorageUnavailable):
                operation()
        with closing(sqlite3.connect(self.path)) as connection, connection:
            self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], 99)
            connection.execute("PRAGMA user_version = 1")
            connection.execute("UPDATE settings SET controls = 'broken JSON'")
        with self.assertRaises(StorageUnavailable):
            self.store.snapshot()

    def test_backup_restore_preserves_identity_knowledge_and_permissions(self):
        self.pair()
        self.store.add_memory(self.note())
        self.store.update_controls({"expectedRevision": self.revision(), "monthlyBudgetCents": 100})
        original = self.store.export()
        backup = Path(self.directory.name) / "backup.sqlite3"
        self.store.backup(backup)
        with self.assertRaises(FileExistsError):
            self.store.backup(backup)
        with self.assertRaises(FileExistsError):
            self.store.backup(self.path)
        self.store.add_memory(self.note(text="Added after the backup"))
        with tempfile.TemporaryDirectory() as restored:
            shutil.copyfile(backup, Path(restored) / DATABASE_NAME)
            recovered = FreeRoamStore(restored, clock=self.store.clock).export()
        self.assertEqual(recovered, original)

    def test_environment_has_no_ephemeral_fallback(self):
        with patch.dict(os.environ, {"FAMILIAR_DATA_DIR": "", "RAILWAY_VOLUME_MOUNT_PATH": self.directory.name}):
            self.assertIsNone(store_from_environment())
        with patch.dict(os.environ, {"FAMILIAR_DATA_DIR": self.directory.name}):
            self.assertEqual(store_from_environment().snapshot(), self.store.snapshot())

    def test_operator_commands_provision_and_back_up_without_resetting_data(self):
        script = Path(__file__).with_name("free_roam.py")
        with tempfile.TemporaryDirectory() as directory:
            provision = subprocess.run([sys.executable, "-B", str(script), "init", "--data-dir", directory],
                                       capture_output=True, text=True, timeout=10)
            self.assertEqual(provision.returncode, 0, provision.stderr)
            store = FreeRoamStore(directory, clock=self.store.clock)
            original = store.pair(self.pairing)
            repeated = subprocess.run([sys.executable, "-B", str(script), "init", "--data-dir", directory],
                                      capture_output=True, text=True, timeout=10)
            self.assertEqual(repeated.returncode, 0, repeated.stderr)
            self.assertEqual(store.snapshot(), original)
            output = str(Path(directory) / "operator-backup.sqlite3")
            backed_up = subprocess.run([sys.executable, "-B", str(script), "backup", "--data-dir", directory,
                                        "--output", output], capture_output=True, text=True, timeout=10)
            self.assertEqual(backed_up.returncode, 0, backed_up.stderr)
            self.assertTrue(Path(output).is_file())


class FreeRoamHttpTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        initialize(self.directory.name)
        self.store = FreeRoamStore(self.directory.name)
        self.calls = []

        def thinker(context, _):
            self.calls.append(copy.deepcopy(context))
            return {"text": "Still dozing."}

        self.server = make_server(("127.0.0.1", 0), "fake-provider-key", TOKEN, thinker, self.store)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, method, path, body=None, token=TOKEN, raw=False, headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        self.addCleanup(connection.close)
        payload = body if raw else None if body is None else json.dumps(body)
        config = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"} | (headers or {})
        connection.request(method, path, payload, config)
        response = connection.getresponse()
        self.assertEqual(response.getheader("Cache-Control"), "no-store")
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def pair(self):
        status, result = self.request("POST", "/free-roam/pair", {"pairingId": new_id(), "createdAt": 1234, "curiosity": 0.65})
        self.assertEqual(status, 200)
        return result

    def test_all_reads_and_writes_require_owner_authentication(self):
        for method, path in (("GET", "/free-roam"), ("GET", "/free-roam/export"),
                             ("GET", "/free-roam/memories"), ("GET", "/free-roam/audit"),
                             ("POST", "/free-roam/pair"), ("POST", "/free-roam/controls"),
                             ("POST", "/free-roam/memories"), ("POST", "/free-roam/interests"),
                             ("POST", "/free-roam/goals"), ("POST", "/free-roam/forget"),
                             ("DELETE", f"/free-roam/memories/{new_id()}")):
            with self.subTest(method=method, path=path):
                self.assertEqual(self.request(method, path, {}, token="wrong"), (401, {"error": "Unauthorized"}))
        self.assertEqual(self.store.snapshot()["revision"], 0)
        self.assertEqual(self.calls, [])

    def test_complete_owner_workflow_makes_no_provider_calls(self):
        state = self.pair()
        status, note = self.request("POST", "/free-roam/memories", {"expectedRevision": state["revision"],
                                   "id": new_id(), "topic": "space", "text": "An owner note about moons."})
        self.assertEqual(status, 200)
        status, interest = self.request("POST", "/free-roam/interests", {"expectedRevision": note["revision"],
                                       "id": new_id(), "topic": "space", "priority": 4})
        self.assertEqual(status, 200)
        status, goal = self.request("POST", "/free-roam/goals", {"expectedRevision": interest["revision"],
                                   "id": new_id(), "topic": "space", "question": "What makes a moon geologically active?",
                                   "status": "active", "priority": 4})
        self.assertEqual(status, 200)
        status, controls = self.request("POST", "/free-roam/controls", {"expectedRevision": goal["revision"],
                                       "webEnabled": True, "allowedDomains": ["nasa.gov"]})
        self.assertEqual(status, 200)
        exported = self.request("GET", "/free-roam/export")[1]
        self.assertEqual(exported["profile"], state["profile"])
        self.assertEqual(len(exported["memories"]), 1)
        self.assertNotIn(TOKEN, json.dumps(exported))
        self.assertNotIn("fake-provider-key", json.dumps(exported))
        status, deleted = self.request("DELETE", f"/free-roam/memories/{note['memory']['id']}", {"expectedRevision": controls["revision"]})
        self.assertEqual(status, 200)
        self.assertTrue(deleted["deleted"])
        self.assertEqual(self.request("GET", "/free-roam/memories?after=0&limit=1")[1]["items"], [])
        self.assertGreater(len(self.request("GET", "/free-roam/audit")[1]["items"]), 0)
        self.assertEqual(self.request("POST", "/free-roam/forget", {"expectedRevision": deleted["revision"],
                                                                  "confirmation": "forget-cognitive-memory"})[0], 200)
        self.assertEqual(self.calls, [])

    def test_legacy_think_does_not_receive_persistent_notes(self):
        state = self.pair()
        self.request("POST", "/free-roam/memories", {"expectedRevision": state["revision"], "id": new_id(),
                                                     "topic": "private", "text": "Do not forward this owner note."})
        before = self.store.export()
        self.assertEqual(self.request("POST", "/think", CONTEXT), (200, {"text": "Still dozing."}))
        self.assertEqual(self.calls, [CONTEXT])
        after = self.store.export()
        self.assertEqual({key: value for key, value in before.items() if key != "exportedAt"},
                         {key: value for key, value in after.items() if key != "exportedAt"})

    def test_invalid_and_stale_writes_are_isolated(self):
        state = self.pair()
        self.assertEqual(self.request("POST", "/free-roam/controls", {"expectedRevision": state["revision"], "enabled": True})[0], 409)
        self.assertEqual(self.request("POST", "/free-roam/controls", {"expectedRevision": state["revision"], "webEnabled": True})[0], 400)
        self.assertEqual(self.request("POST", "/free-roam/controls", {"expectedRevision": 0, "dailyWakeLimit": 2})[0], 409)
        self.assertEqual(self.store.snapshot(), state)

    def test_json_and_request_size_boundaries_reject_ambiguous_input(self):
        cases = (('{"pairingId":"a","pairingId":"b"}', {}), ('{"curiosity":NaN}', {}),
                 ("[" * 2000 + "0" + "]" * 2000, {}),
                 ("[]", {}), ("not JSON", {}), ("x" * 16385, {}), ("{}", {"Content-Type": "text/plain"}))
        for body, headers in cases:
            with self.subTest(body=body[:40]):
                self.assertEqual(self.request("POST", "/free-roam/pair", body, raw=True, headers=headers)[0], 400)
        self.assertIsNone(self.store.snapshot()["profile"])

    def test_duplicate_framing_and_chunked_writes_are_rejected(self):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        self.addCleanup(connection.close)
        connection.putrequest("POST", "/free-roam/pair")
        connection.putheader("Authorization", f"Bearer {TOKEN}")
        connection.putheader("Content-Type", "application/json")
        connection.putheader("Content-Length", "2")
        connection.putheader("Content-Length", "2")
        connection.endheaders(b"{}")
        response = connection.getresponse()
        self.assertEqual(response.status, 400)
        response.read()
        connection.close()
        self.assertEqual(self.request("POST", "/free-roam/pair", "{}", raw=True,
                                      headers={"Transfer-Encoding": "chunked"})[0], 400)
        self.assertIsNone(self.store.snapshot()["profile"])

    def test_pagination_rejects_unbounded_or_duplicate_parameters(self):
        for query in ("limit=51", "after=-1", "limit=1&limit=2", "limit=", "unknown=1", "after=" + "9" * 30):
            with self.subTest(query=query):
                self.assertEqual(self.request("GET", "/free-roam/memories?" + query)[0], 400)

    def test_storage_loss_returns_sanitized_errors_and_preserves_voice(self):
        self.pair()
        (Path(self.directory.name) / DATABASE_NAME).unlink()
        self.assertEqual(self.request("GET", "/free-roam/export"), (503, {"error": "Free Roam storage unavailable"}))
        self.assertFalse((Path(self.directory.name) / DATABASE_NAME).exists())
        self.assertEqual(self.request("POST", "/think", CONTEXT)[0], 200)
        self.assertEqual(self.request("GET", "/health"), (200, {"status": "ok"}))

    def test_unconfigured_storage_does_not_affect_legacy_gateway(self):
        self.stop_server()
        self.server = make_server(("127.0.0.1", 0), "fake-provider-key", TOKEN, lambda *_: {"text": "Dozing."})
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.assertEqual(self.request("GET", "/free-roam"), (503, {"error": "Free Roam storage unavailable"}))
        self.assertEqual(self.request("GET", "/free-roam", token="wrong")[0], 401)
        self.assertEqual(self.request("POST", "/think", CONTEXT), (200, {"text": "Dozing."}))


if __name__ == "__main__":
    unittest.main()
