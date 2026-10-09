import copy
import json
import shutil
import sqlite3
import tempfile
import threading
import unittest
import urllib.error
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from free_roam import (DATABASE_NAME, DEFAULT_CONTROLS, SCHEMA, WAKE_SCHEMA, FreeRoamStore,
                       StorageUnavailable, WAKE_LEASE_MS, WAKE_RESERVATION_MICROUSD,
                       initialize, migrate)
import test_free_roam
from test_free_roam import new_id
from wake_cycles import (CycleInvalid, MAX_INPUT_BYTES, MAX_OUTPUT_TOKENS, MODEL, WakeWorker,
                         decision_payload, payload, permitted_source, request_response,
                         usage_cost, validate_decision, validate_research)


def decision(action="reflect", **changes):
    return {"action": action, "topic": "navigation", "reason": "I am curious about my earlier question.",
            "question": "How do migrating animals navigate?" if action in ("explore", "research") else "",
            "reflection": "I wonder whether different animals use different senses." if action == "reflect" else ""} | changes


def response(content, usage=None, **changes):
    return {"model": MODEL, "status": "completed", "usage": usage or {"input_tokens": 2000, "output_tokens": 150},
            "output": [{"type": "message", "content": [{"type": "output_text", "text": content, "annotations": []}]}]} | changes


def plan(action="reflect", **changes):
    return response(json.dumps(decision(action, **changes)))


def research(url="https://www.nasa.gov/science", **changes):
    result = response("I found a description of navigation research. The mechanisms remain uncertain.")
    result["output"][0]["content"][0]["annotations"] = [{"type": "url_citation", "url": url,
        "title": "Navigation research", "start_index": 0, "end_index": 43}]
    result["output"].insert(0, {"type": "web_search_call", "status": "completed",
        "action": {"type": "search", "sources": [{"type": "url", "url": url}]}})
    return result | changes


class WakeTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        initialize(self.directory.name)
        self.now = int(datetime(2026, 10, 9, tzinfo=timezone.utc).timestamp() * 1000)
        self.store = FreeRoamStore(self.directory.name, clock=lambda: self.now)
        self.profile = self.store.pair({"pairingId": new_id(), "createdAt": 1_790_000_000_000,
                                       "curiosity": 0.65})["profile"]
        self.calls = []

    def controls(self, **changes):
        return self.store.update_controls({"expectedRevision": self.store.snapshot()["revision"]} | changes)

    def enable(self, **changes):
        return self.controls(enabled=True, **changes)

    def worker(self, *responses):
        pending = iter(responses or (plan(),))

        def provider(request):
            self.calls.append(copy.deepcopy(request))
            result = next(pending)
            if isinstance(result, Exception):
                raise result
            return copy.deepcopy(result)

        return WakeWorker(self.store, "fake-key", provider=provider)

    def test_disabled_and_unpaired_workers_make_no_calls(self):
        self.assertEqual(self.worker().run_once(), "idle")
        self.assertEqual(self.calls, [])
        with tempfile.TemporaryDirectory() as directory:
            initialize(directory)
            store = FreeRoamStore(directory)
            self.assertIsNone(store.claim_wake())
        self.assertEqual(self.store.page("journal")["items"], [])

    def test_reflection_becomes_labeled_memory_without_changing_identity_or_goals(self):
        self.enable()
        self.assertEqual(self.worker().run_once(), "completed")
        state = self.store.export()
        self.assertEqual(state["profile"], self.profile)
        self.assertEqual(state["goals"], [])
        memory, event = state["memories"][0], state["journal"][0]
        self.assertEqual(memory["kind"], "reflection")
        self.assertEqual(memory["sources"], [])
        self.assertEqual(memory["text"], decision()["reflection"])
        self.assertEqual(memory["id"], event["id"])
        self.assertEqual(event["action"], "reflect")
        self.assertEqual(event["modelCalls"], 1)
        self.assertEqual(event["chargedMicrousd"], usage_cost(plan(), False))
        self.assertNotIn("tools", self.calls[0])
        self.assertFalse(self.calls[0]["store"])
        self.assertEqual(self.calls[0]["model"], MODEL)

    def test_rest_is_a_real_model_decision_and_creates_no_fake_experience(self):
        self.enable()
        self.assertEqual(self.worker(plan("rest")).run_once(), "completed")
        self.assertEqual(self.store.page("journal")["items"][0]["action"], "rest")
        self.assertEqual(self.store.export()["memories"], [])

    def test_exploration_and_research_use_one_domain_filtered_search_and_keep_evidence(self):
        self.enable(webEnabled=True, allowedDomains=["nasa.gov"])
        for action in ("explore", "research"):
            self.assertEqual(self.worker(plan(action), research()).run_once(), "completed")
            self.now += 6 * 3_600_000
        state = self.store.export()
        self.assertEqual(len(self.calls), 4)
        self.assertEqual([item["kind"] for item in state["memories"]], ["finding", "finding"])
        self.assertEqual(state["memories"][0]["sources"][0]["url"], "https://www.nasa.gov/science")
        for request in self.calls[1::2]:
            self.assertEqual(request["max_tool_calls"], 1)
            self.assertEqual(request["tool_choice"], "required")
            self.assertFalse(request["parallel_tool_calls"])
            self.assertEqual(request["tools"][0]["type"], "web_search")
            self.assertEqual(request["tools"][0]["filters"], {"allowed_domains": ["nasa.gov"]})
            self.assertEqual(request["include"], ["web_search_call.action.sources"])
        self.assertEqual([item["modelCalls"] for item in state["journal"]], [2, 2])
        self.assertEqual(state["journal"][0]["question"], decision("explore")["question"])

    def test_no_web_permission_cannot_be_overridden_by_model(self):
        self.enable()
        self.assertEqual(self.worker(plan("research"), research()).run_once(), "failed")
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.store.export()["memories"], [])
        self.assertEqual(self.store.page("journal")["items"][0]["errorCode"], "invalid_decision")

    def test_missing_or_fabricated_evidence_never_becomes_a_finding(self):
        self.enable(webEnabled=True, allowedDomains=["nasa.gov"])
        cases = [response("I read an imaginary paper."), research("https://evil.test/page")]
        no_citation = research()
        no_citation["output"][1]["content"][0]["annotations"] = []
        cases.append(no_citation)
        fabricated = research()
        fabricated["output"][1]["content"][0]["annotations"][0]["url"] = "https://nasa.gov/unseen"
        cases.append(fabricated)
        too_many_calls = research()
        too_many_calls["output"].append(copy.deepcopy(too_many_calls["output"][0]))
        cases.append(too_many_calls)
        for invalid in cases:
            with self.subTest(invalid=invalid):
                self.assertEqual(self.worker(plan("explore"), invalid).run_once(), "failed")
                self.assertEqual(self.store.export()["memories"], [])
                self.now += 24 * 3_600_000

    def test_memory_and_goal_context_reaches_future_decisions_without_phone_or_identity_metadata(self):
        revision = self.store.snapshot()["revision"]
        self.store.add_memory({"expectedRevision": revision, "id": new_id(), "topic": "navigation", "text": "An owner question."})
        self.store.save_interest({"expectedRevision": self.store.snapshot()["revision"], "id": new_id(), "topic": "birds", "priority": 4})
        self.store.save_goal({"expectedRevision": self.store.snapshot()["revision"], "id": new_id(), "topic": "birds",
                              "question": "How do birds navigate?", "status": "active", "priority": 4})
        self.enable()
        self.worker().run_once()
        self.now += 6 * 3_600_000
        self.worker(plan("rest")).run_once()
        context = json.loads(self.calls[-1]["input"])
        self.assertEqual(context["curiosity"], 0.65)
        self.assertEqual(context["goals"][0]["question"], "How do birds navigate?")
        self.assertEqual(context["interests"][0]["topic"], "birds")
        self.assertEqual({item["kind"] for item in context["memories"]}, {"owner_note", "reflection"})
        self.assertEqual(context["recentJournal"][0]["action"], "reflect")
        for field in ("createdAt", "pairingId", "familiarId", "environment", "energy", "stimulation", "appUsage", "startedAt"):
            self.assertNotIn(field, self.calls[-1]["input"])

    def test_restart_and_clock_rollback_do_not_duplicate_wakes_or_reset_accounting(self):
        self.enable()
        self.worker().run_once()
        original = self.store.export()
        self.store = FreeRoamStore(self.directory.name, clock=lambda: self.now)
        self.assertEqual(self.worker().run_once(), "idle")
        self.now -= 60_000
        self.assertEqual(self.worker().run_once(), "idle")
        self.assertEqual(self.store.export()["journal"], original["journal"])
        self.assertEqual(self.store.snapshot()["execution"]["monthlyCharges"], original["execution"]["monthlyCharges"])
        self.now += 6 * 3_600_000 + 60_000
        self.assertEqual(self.worker().run_once(), "completed")

    def test_atomic_claim_allows_only_one_worker(self):
        self.enable()
        def claim(_):
            return FreeRoamStore(self.directory.name, clock=lambda: self.now).claim_wake()
        with ThreadPoolExecutor(max_workers=8) as pool:
            claims = list(pool.map(claim, range(8)))
        self.assertEqual(sum(claim is not None for claim in claims), 1)
        self.assertEqual(len(self.store.export()["journal"]), 1)
        self.assertEqual(self.store.snapshot()["execution"]["monthlyCharges"][0]["chargedMicrousd"], WAKE_RESERVATION_MICROUSD)

    def test_daily_limit_is_durable_and_rolls_over_at_utc_midnight(self):
        self.enable(dailyWakeLimit=1)
        self.worker().run_once()
        self.now += 6 * 3_600_000
        self.assertEqual(self.worker().run_once(), "idle")
        self.now += 18 * 3_600_000
        self.assertEqual(self.worker().run_once(), "completed")

    def test_no_catchup_burst_after_long_server_downtime(self):
        self.enable()
        self.worker().run_once()
        self.now += 30 * 24 * 3_600_000
        self.assertEqual(self.worker().run_once(), "completed")
        self.assertEqual(self.worker().run_once(), "idle")
        self.assertEqual(len(self.store.export()["journal"]), 2)

    def test_zero_or_insufficient_monthly_budget_never_calls_provider(self):
        self.enable(monthlyBudgetCents=0)
        self.assertEqual(self.worker().run_once(), "idle")
        self.controls(monthlyBudgetCents=4)
        self.assertEqual(self.worker().run_once(), "idle")
        self.assertEqual(self.calls, [])

    def test_unknown_usage_consumes_reservation_without_automatic_retry(self):
        self.enable(monthlyBudgetCents=5)
        self.assertEqual(self.worker(TimeoutError("secret provider body")).run_once(), "failed")
        item = self.store.page("journal")["items"][0]
        self.assertEqual(item["chargedMicrousd"], WAKE_RESERVATION_MICROUSD)
        self.assertEqual(item["errorCode"], "provider_timeout")
        self.assertNotIn("secret", json.dumps(self.store.export()))
        self.now += 6 * 3_600_000
        self.assertEqual(self.worker().run_once(), "idle")
        self.now = int(datetime(2026, 11, 1, tzinfo=timezone.utc).timestamp() * 1000)
        self.assertEqual(self.worker().run_once(), "completed")

    def test_interrupted_claim_is_not_replayed_or_refunded(self):
        self.enable()
        claim = self.store.claim_wake()
        self.store.begin_wake_call(claim)
        self.now += WAKE_LEASE_MS
        self.assertIsNone(self.store.claim_wake())
        interrupted = self.store.page("journal")["items"][0]
        self.assertEqual(interrupted["status"], "interrupted")
        self.assertEqual(interrupted["chargedMicrousd"], WAKE_RESERVATION_MICROUSD)
        self.assertFalse(self.store.begin_wake_call(claim))
        self.assertIsNone(self.store.finish_wake(claim))
        self.now += 6 * 3_600_000
        self.assertIsNotNone(self.store.claim_wake())

    def test_provider_http_failure_is_observable_without_body_or_retry(self):
        self.enable()
        failure = urllib.error.HTTPError("https://api.openai.com/v1/responses", 429, "private provider body", {}, None)
        self.assertEqual(self.worker(failure).run_once(), "failed")
        entry = self.store.page("journal")["items"][0]
        self.assertEqual(entry["errorCode"], "provider_http_429")
        self.assertEqual(entry["modelCalls"], 1)
        self.assertEqual(entry["chargedMicrousd"], WAKE_RESERVATION_MICROUSD)
        self.assertNotIn("private provider body", json.dumps(self.store.export()))

    def test_pause_between_decision_and_search_cancels_without_a_second_call(self):
        self.enable(webEnabled=True, allowedDomains=["nasa.gov"])
        def provider(request):
            self.calls.append(request)
            self.controls(enabled=False)
            return plan("research")
        self.assertEqual(WakeWorker(self.store, "fake-key", provider).run_once(), "cancelled")
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.store.export()["memories"], [])

    def test_owner_edit_during_search_discards_the_result(self):
        self.enable(webEnabled=True, allowedDomains=["nasa.gov"])
        def provider(request):
            self.calls.append(request)
            if len(self.calls) == 1:
                return plan("research")
            self.controls(allowedDomains=["python.org"])
            return research()
        self.assertEqual(WakeWorker(self.store, "fake-key", provider).run_once(), "cancelled")
        self.assertEqual(self.store.export()["memories"], [])
        self.assertIsNone(self.store.page("journal")["items"][0]["summary"])

    def test_forget_during_search_cannot_resurrect_content_or_reset_spending(self):
        self.enable(webEnabled=True, allowedDomains=["nasa.gov"])
        def provider(request):
            self.calls.append(request)
            if len(self.calls) == 1:
                return plan("research")
            self.store.forget({"expectedRevision": self.store.snapshot()["revision"], "confirmation": "forget-cognitive-memory"})
            return research()
        self.assertEqual(WakeWorker(self.store, "fake-key", provider).run_once(), "cancelled")
        exported = self.store.export()
        self.assertEqual(exported["memories"], [])
        self.assertEqual(exported["journal"], [])
        self.assertEqual(exported["profile"], self.profile)
        self.assertGreater(exported["execution"]["monthlyCharges"][0]["chargedMicrousd"], 0)
        self.enable()
        self.assertEqual(self.worker().run_once(), "idle")

    def test_deleting_an_experience_also_removes_journal_content_but_keeps_receipt(self):
        self.enable()
        self.worker().run_once()
        exported = self.store.export()
        item = exported["memories"][0]
        self.store.delete_entry("memories", item["id"], {"expectedRevision": exported["revision"]})
        after = self.store.export()
        self.assertEqual(after["memories"], [])
        self.assertEqual(after["journal"], [])
        self.assertNotIn(item["text"], json.dumps(after))
        self.assertEqual(after["execution"]["monthlyCharges"], exported["execution"]["monthlyCharges"])

    def test_budget_overrun_pauses_further_execution(self):
        self.enable()
        raw = plan()
        raw["usage"]["input_tokens"] = 1_000_000
        self.worker(raw).run_once()
        self.assertFalse(self.store.snapshot()["controls"]["enabled"])
        self.assertGreater(self.store.page("journal")["items"][0]["chargedMicrousd"], WAKE_RESERVATION_MICROUSD)

    def test_memory_and_journal_capacity_halt_without_dropping_history(self):
        self.enable()
        with patch("free_roam.MAX_MEMORIES", 0):
            self.assertIsNone(self.store.claim_wake())
        with patch("free_roam.MAX_WAKE_RUNS", 0):
            self.assertIsNone(self.store.claim_wake())
        self.assertEqual(self.store.page("journal")["items"], [])

    def test_unicode_memory_context_remains_bounded(self):
        for index in range(8):
            self.store.add_memory({"expectedRevision": self.store.snapshot()["revision"], "id": new_id(),
                                  "topic": str(index), "text": "\U0001f426" * 2000})
        self.enable()
        request = decision_payload(self.store.claim_wake())
        self.assertLessEqual(len(json.dumps(request, ensure_ascii=False).encode()), MAX_INPUT_BYTES)
        self.assertGreater(len(json.loads(request["input"])["memories"]), 0)

    def test_each_wake_is_limited_to_two_calls_and_lease_time(self):
        self.enable(webEnabled=True, allowedDomains=["nasa.gov"])
        claim = self.store.claim_wake()
        self.assertTrue(self.store.begin_wake_call(claim))
        self.assertTrue(self.store.begin_wake_call(claim, web=True))
        self.assertFalse(self.store.begin_wake_call(claim, web=True))
        self.now += WAKE_LEASE_MS
        self.assertFalse(self.store.begin_wake_call(claim))

    def test_deleting_an_absent_memory_does_not_change_a_running_journal(self):
        self.enable()
        claim = self.store.claim_wake()
        result = self.store.delete_entry("memories", claim["id"], {"expectedRevision": claim["revision"]})
        self.assertFalse(result["deleted"])
        self.assertEqual(self.store.page("journal")["items"][0]["status"], "running")
        self.assertTrue(self.store.begin_wake_call(claim))

    def test_deleting_journal_content_also_forgets_its_experience(self):
        self.enable()
        self.worker().run_once()
        state = self.store.export()
        self.store.delete_entry("journal", state["journal"][0]["id"], {"expectedRevision": state["revision"]})
        self.assertEqual(self.store.export()["memories"], [])
        self.assertEqual(self.store.page("journal")["items"], [])

    def test_backup_preserves_wake_schedule_and_budget(self):
        self.enable()
        self.worker().run_once()
        backup = Path(self.directory.name) / "backup.sqlite3"
        self.store.backup(backup)
        with tempfile.TemporaryDirectory() as restored:
            shutil.copyfile(backup, Path(restored) / DATABASE_NAME)
            recovered = FreeRoamStore(restored, clock=lambda: self.now)
            self.assertEqual(recovered.export(), self.store.export())
            self.assertIsNone(recovered.claim_wake())

    def test_background_thread_can_wake_without_android_and_stop(self):
        self.enable()
        done = threading.Event()
        def provider(request):
            done.set()
            return plan("rest")
        worker = WakeWorker(self.store, "fake-key", provider)
        worker.start()
        self.addCleanup(worker.stop)
        self.assertTrue(done.wait(2))
        worker.stop()
        self.assertFalse(worker.thread.is_alive())
        self.assertEqual(self.store.page("journal")["items"][0]["status"], "completed")


class ProviderBoundaryTests(unittest.TestCase):
    def test_strict_decision_contract_rejects_injected_actions_or_privileges(self):
        for changes in ({"action": "set_controls"}, {"reflection": "x" * 1001}, {"question": "unexpected"},
                        {"enabled": True}, {"topic": ""}, {"action": "research"}):
            with self.subTest(changes=changes), self.assertRaises(CycleInvalid):
                validate_decision(response(json.dumps(decision() | changes)), False)

    def test_source_domain_comparison_rejects_suffix_tricks_and_unsafe_urls(self):
        for url in ("http://nasa.gov/a", "https://nasa.gov.evil.test/a", "https://evilnasa.gov/a",
                    "https://user@nasa.gov/a", "https://nasa.gov:8080/a", "https://127.0.0.1/a",
                    "https://nasa.gov/a\n", "https://nasa.gov\\@evil.test/a", "https://evil.test\\.nasa.gov/a"):
            with self.subTest(url=url), self.assertRaises((CycleInvalid, ValueError)):
                permitted_source(url, ["nasa.gov"])
        self.assertEqual(permitted_source("https://science.nasa.gov/a", ["nasa.gov"]), "https://science.nasa.gov/a")

    def test_refusal_incomplete_response_and_invalid_usage_are_rejected(self):
        with self.assertRaises(CycleInvalid):
            validate_decision(plan(status="incomplete"), False)
        refused = response("")
        refused["output"][0]["content"] = [{"type": "refusal", "refusal": "No."}]
        with self.assertRaises(CycleInvalid):
            validate_decision(refused, False)
        for usage in ({"input_tokens": True, "output_tokens": 2}, {"input_tokens": -1, "output_tokens": 2}, {}):
            with self.assertRaises((ValueError, KeyError)):
                usage_cost({"model": MODEL, "usage": usage}, False)
        with self.assertRaises(CycleInvalid):
            usage_cost(response("x", model="unpriced-model"), False)

    def test_largest_payload_and_fixed_search_cost_fit_the_reservation(self):
        bound = ((MAX_INPUT_BYTES + 4096) * 2 + 8000) * 0.4 + MAX_OUTPUT_TOKENS * 2 * 1.6 + 13_200
        self.assertLess(bound, WAKE_RESERVATION_MICROUSD)
        self.assertEqual(usage_cost(response("x"), True) - usage_cost(response("x"), False), 13_200)
        with self.assertRaises(CycleInvalid):
            payload("x", {"data": "x" * MAX_INPUT_BYTES})

    def test_native_request_is_bounded_and_not_retried(self):
        mocked = unittest.mock.MagicMock()
        mocked.open.return_value.__enter__.return_value.read.return_value = json.dumps(plan()).encode()
        with patch("urllib.request.build_opener", return_value=mocked):
            self.assertEqual(request_response(payload("instructions", {}), "fake-key"), plan())
        self.assertEqual(mocked.open.call_count, 1)
        self.assertEqual(mocked.open.call_args.kwargs["timeout"], 25)
        request = mocked.open.call_args.args[0]
        self.assertEqual(request.full_url, "https://api.openai.com/v1/responses")
        self.assertNotIn("previous_response_id", json.loads(request.data))

    def test_missing_consulted_sources_and_invalid_citation_offsets_are_rejected(self):
        raw = research()
        raw["output"][0]["action"]["sources"] = []
        with self.assertRaises(CycleInvalid):
            validate_research(raw, ["nasa.gov"])
        raw = research()
        raw["output"][1]["content"][0]["annotations"][0]["end_index"] = 5000
        with self.assertRaises(ValueError):
            validate_research(raw, ["nasa.gov"])

    def test_network_failure_has_one_attempt_and_oversized_body_is_rejected(self):
        mocked = unittest.mock.MagicMock()
        mocked.open.side_effect = TimeoutError()
        with patch("urllib.request.build_opener", return_value=mocked), self.assertRaises(TimeoutError):
            request_response(payload("instructions", {}), "fake-key")
        self.assertEqual(mocked.open.call_count, 1)
        mocked = unittest.mock.MagicMock()
        mocked.open.return_value.__enter__.return_value.read.return_value = b"x" * 65_537
        with patch("urllib.request.build_opener", return_value=mocked), self.assertRaises(CycleInvalid):
            request_response(payload("instructions", {}), "fake-key")


class JournalHttpTests(unittest.TestCase):
    # Reuse the HTTP fixture without rediscovering its legacy test cases.
    setUp = test_free_roam.FreeRoamHttpTests.setUp
    stop_server = test_free_roam.FreeRoamHttpTests.stop_server
    request = test_free_roam.FreeRoamHttpTests.request
    pair = test_free_roam.FreeRoamHttpTests.pair

    def test_journal_authentication_pagination_deletion_and_legacy_voice_isolation(self):
        state = self.pair()
        self.assertEqual(self.request("GET", "/free-roam/journal", token="wrong")[0], 401)
        self.assertEqual(self.request("POST", "/free-roam/controls",
                                     {"expectedRevision": state["revision"], "enabled": True})[0], 200)
        self.assertEqual(WakeWorker(self.store, "fake-key", lambda _: plan()).run_once(), "completed")
        status, page = self.request("GET", "/free-roam/journal?limit=1")
        self.assertEqual(status, 200)
        self.assertEqual(len(page["items"]), 1)
        self.assertEqual(page["items"][0]["status"], "completed")
        self.assertIsNone(page["nextCursor"])
        for query in ("limit=51", "after=-1", "limit=1&limit=2"):
            self.assertEqual(self.request("GET", "/free-roam/journal?" + query)[0], 400)
        entry_id = page["items"][0]["id"]
        self.assertEqual(self.request("DELETE", "/free-roam/journal/" + entry_id,
                                     {"expectedRevision": page["revision"]}, token="wrong")[0], 401)
        status, deleted = self.request("DELETE", "/free-roam/journal/" + entry_id,
                                      {"expectedRevision": page["revision"]})
        self.assertEqual(status, 200)
        self.assertTrue(deleted["deleted"])
        self.assertEqual(self.request("GET", "/free-roam/journal")[1]["items"], [])
        self.assertEqual(self.request("GET", "/free-roam/memories")[1]["items"], [])
        self.assertEqual(self.request("GET", "/health")[0], 200)
        from test_server import CONTEXT
        self.assertEqual(self.request("POST", "/think", CONTEXT)[0], 200)
        self.assertEqual(self.calls, [CONTEXT])

    def test_making_a_server_does_not_start_or_execute_a_worker(self):
        state = self.pair()
        self.request("POST", "/free-roam/controls", {"expectedRevision": state["revision"], "enabled": True})
        self.assertEqual(self.request("GET", "/free-roam/journal")[1]["items"], [])
        self.assertEqual(self.calls, [])


class MigrationTests(unittest.TestCase):
    def test_existing_backup_is_protected_and_failed_upgrade_rolls_back(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / DATABASE_NAME
            with closing(sqlite3.connect(path)) as connection, connection:
                connection.executescript(SCHEMA.removesuffix(WAKE_SCHEMA) + "PRAGMA user_version = 1;")
                connection.execute("INSERT INTO settings VALUES (1, 0, ?)", (json.dumps(DEFAULT_CONTROLS),))
                # Simulate an incompatible partial schema. ALTERs must also roll back.
                connection.execute("CREATE TABLE wake_budget(unrelated TEXT)")
            backup = Path(directory) / "protected.sqlite3"
            backup.write_bytes(b"existing backup")
            with self.assertRaises(FileExistsError):
                migrate(directory, backup)
            self.assertEqual(backup.read_bytes(), b"existing backup")
            with self.assertRaises(sqlite3.OperationalError):
                migrate(directory, Path(directory) / "before-failure.sqlite3")
            with closing(sqlite3.connect(path)) as connection:
                self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], 1)
                columns = [row[1] for row in connection.execute("PRAGMA table_info(memories)")]
                self.assertNotIn("kind", columns)
                self.assertIsNone(connection.execute("SELECT name FROM sqlite_master WHERE name = 'wake_runs'").fetchone())

    def test_explicit_upgrade_backs_up_and_preserves_v1_identity_and_notes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / DATABASE_NAME
            familiar_id, pairing_id, note_id = new_id(), new_id(), new_id()
            with closing(sqlite3.connect(path)) as connection, connection:
                connection.executescript(SCHEMA.removesuffix(WAKE_SCHEMA) + "PRAGMA user_version = 1;")
                connection.execute("INSERT INTO settings VALUES (1, 7, ?)", (json.dumps(DEFAULT_CONTROLS),))
                connection.execute("INSERT INTO profile VALUES (1, ?, ?, 1790000000000, 0.65, 1800000000000)", (familiar_id, pairing_id))
                connection.execute("INSERT INTO memories(id, topic, text, created_at) VALUES (?, 'birds', 'Existing owner note.', 1800000000000)", (note_id,))
            store = FreeRoamStore(directory)
            with self.assertRaises(StorageUnavailable):
                store.snapshot()
            backup = Path(directory) / "before-v2.sqlite3"
            migrate(directory, backup)
            state = store.export()
            self.assertEqual(state["schemaVersion"], 2)
            self.assertEqual(state["profile"]["familiarId"], familiar_id)
            self.assertEqual(state["profile"]["pairingId"], pairing_id)
            self.assertEqual(state["profile"]["curiosity"], 0.65)
            self.assertEqual(state["revision"], 7)
            self.assertEqual(state["controls"], DEFAULT_CONTROLS)
            self.assertEqual(state["memories"][0]["text"], "Existing owner note.")
            self.assertEqual(state["memories"][0]["kind"], "owner_note")
            with closing(sqlite3.connect(backup)) as connection:
                self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], 1)
                self.assertEqual(connection.execute("SELECT text FROM memories").fetchone()[0], "Existing owner note.")
            migrate(directory, Path(directory) / "not-needed.sqlite3")
            self.assertFalse((Path(directory) / "not-needed.sqlite3").exists())


if __name__ == "__main__":
    unittest.main()
