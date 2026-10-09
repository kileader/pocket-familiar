"""Owner-controlled cognitive storage and durable wake accounting. No body simulation."""
import argparse
import json
import math
import os
import re
import sqlite3
import time
import uuid
from contextlib import closing, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

SCHEMA_VERSION = 2
DATABASE_NAME = "free-roam.sqlite3"
MAX_MEMORIES = 1000
MAX_INTERESTS = 10
MAX_ACTIVE_GOALS = 3
MAX_GOALS = 100
MAX_AUDIT_ENTRIES = 1000
MAX_WAKE_RUNS = 1000
WAKE_RESERVATION_MICROUSD = 50_000  # Five cents, reserved before any provider request.
WAKE_LEASE_MS = 300_000
DEFAULT_CONTROLS = {
    "enabled": False,
    "webEnabled": False,
    "allowedDomains": [],
    "wakeIntervalHours": 6,
    "dailyWakeLimit": 4,
    "monthlyBudgetCents": 500,
}
RUNTIME = {"phase": "bounded-wake-cycles", "autonomousExecutionAvailable": True,
           "webToolsAvailable": True, "maxModelCallsPerWake": 2, "maxWebCallsPerWake": 1}
WAKE_SCHEMA = """
ALTER TABLE memories ADD COLUMN kind TEXT NOT NULL DEFAULT 'owner_note';
ALTER TABLE memories ADD COLUMN sources TEXT NOT NULL DEFAULT '[]';
CREATE TABLE wake_runs (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    started_at INTEGER NOT NULL,
    finished_at INTEGER,
    day TEXT NOT NULL,
    month TEXT NOT NULL,
    status TEXT NOT NULL,
    revision INTEGER NOT NULL,
    calls INTEGER NOT NULL DEFAULT 0,
    metered_microusd INTEGER NOT NULL DEFAULT 0,
    charged_microusd INTEGER NOT NULL,
    visible INTEGER NOT NULL DEFAULT 1,
    action TEXT,
    topic TEXT,
    reason TEXT,
    question TEXT,
    summary TEXT,
    sources TEXT NOT NULL DEFAULT '[]',
    error_code TEXT
);
CREATE TABLE wake_budget (
    month TEXT PRIMARY KEY,
    charged_microusd INTEGER NOT NULL CHECK (charged_microusd >= 0)
);
PRAGMA user_version = 2;
"""
SCHEMA = """
BEGIN IMMEDIATE;
CREATE TABLE profile (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    familiar_id TEXT NOT NULL UNIQUE,
    pairing_id TEXT NOT NULL UNIQUE,
    creature_created_at INTEGER NOT NULL CHECK (creature_created_at >= 0),
    curiosity REAL NOT NULL CHECK (curiosity BETWEEN 0 AND 1),
    paired_at INTEGER NOT NULL
);
CREATE TABLE settings (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    revision INTEGER NOT NULL CHECK (revision >= 0),
    controls TEXT NOT NULL
);
CREATE TABLE memories (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    topic TEXT NOT NULL,
    text TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE interests (
    id TEXT PRIMARY KEY,
    topic TEXT NOT NULL,
    topic_key TEXT NOT NULL UNIQUE,
    priority INTEGER NOT NULL CHECK (priority BETWEEN 1 AND 5),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE goals (
    id TEXT PRIMARY KEY,
    topic TEXT NOT NULL,
    question TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('active', 'paused', 'completed')),
    priority INTEGER NOT NULL CHECK (priority BETWEEN 1 AND 5),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE audit (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    action TEXT NOT NULL,
    details TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
""" + WAKE_SCHEMA


class Conflict(ValueError):
    """A valid request would overwrite identity, stale state, or a protected limit."""


class StorageUnavailable(RuntimeError):
    """Persistent storage is missing, incompatible, busy, or unreadable."""


def fields(raw, required, optional=()):
    if not isinstance(raw, dict) or not set(required) <= raw.keys() or not raw.keys() <= set(required) | set(optional):
        raise ValueError("Unexpected or missing fields")


def integer(value, minimum, maximum):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError("Invalid integer")
    return value


def identifier(value):
    if not isinstance(value, str):
        raise ValueError("Expected a UUID")
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        raise ValueError("Expected a UUID") from None
    if str(parsed) != value or parsed.version != 4:
        raise ValueError("Expected a canonical UUID v4")
    return value


def text(value, maximum, multiline=False):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ValueError("Invalid text")
    if any((ord(char) < 32 and not (multiline and char in "\n\t")) or ord(char) == 127 for char in value):
        raise ValueError("Invalid text")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError:
        raise ValueError("Invalid text") from None
    return value.strip() if multiline else " ".join(value.split())


def domains(values):
    if not isinstance(values, list) or len(values) > 20:
        raise ValueError("Expected at most 20 domains")
    normalized = []
    for value in values:
        if not isinstance(value, str) or len(value) > 253:
            raise ValueError("Invalid domain")
        domain = value.lower()
        labels = domain.split(".")
        if len(labels) < 2 or any(not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in labels):
            raise ValueError("Use a public domain without a scheme, port, path, or wildcard")
        if labels[-1].isdigit() or domain.endswith((".local", ".localhost", ".internal")):
            raise ValueError("Use a public domain")
        if urlsplit("https://" + domain).hostname != domain or domain in normalized:
            raise ValueError("Invalid or duplicate domain")
        normalized.append(domain)
    return normalized


def validate_controls(controls):
    fields(controls, DEFAULT_CONTROLS)
    for key in ("enabled", "webEnabled"):
        if type(controls[key]) is not bool:
            raise ValueError("Expected a boolean")
    for key, minimum, maximum in (("wakeIntervalHours", 6, 168), ("dailyWakeLimit", 1, 4),
                                  ("monthlyBudgetCents", 0, 10000)):
        integer(controls[key], minimum, maximum)
    controls = controls | {"allowedDomains": domains(controls["allowedDomains"])}
    if controls["webEnabled"] and not controls["allowedDomains"]:
        raise ValueError("Web permission requires at least one allowed domain")
    return controls


def database_path(data_dir):
    directory = Path(data_dir)
    if not directory.is_absolute() or not directory.is_dir():
        raise StorageUnavailable("Use an existing absolute data directory")
    return directory / DATABASE_NAME


def initialize(data_dir):
    """Explicit provisioning only. Existing files are verified, never reset or replaced."""
    path = database_path(data_dir)
    if path.exists():
        FreeRoamStore(data_dir).snapshot()
        return path
    try:
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(descriptor)
    except FileExistsError:
        FreeRoamStore(data_dir).snapshot()
        return path
    with closing(sqlite3.connect(path)) as connection, connection:
        connection.executescript(SCHEMA)
        connection.execute("INSERT INTO settings VALUES (1, 0, ?)", (json.dumps(DEFAULT_CONTROLS),))
    return path


def migrate(data_dir, backup_path):
    """Explicit, additive v1 upgrade. Preserve a consistent backup before changing schema."""
    path = database_path(data_dir)
    with closing(sqlite3.connect(path.as_uri() + "?mode=rw", uri=True)) as connection:
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        if version == SCHEMA_VERSION:
            FreeRoamStore(data_dir).snapshot()
            return
        if version != 1:
            raise StorageUnavailable("Unsupported Free Roam schema")
        connection.row_factory = sqlite3.Row
        FreeRoamStore._settings(connection)
        target = Path(backup_path)
        if not target.is_absolute() or not target.parent.is_dir():
            raise ValueError("Use an absolute backup path")
        descriptor = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(descriptor)
        with closing(sqlite3.connect(target)) as copy:
            connection.backup(copy)
        try:
            connection.executescript("BEGIN IMMEDIATE;\n" + WAKE_SCHEMA)
            connection.commit()
        except Exception:
            connection.rollback()
            raise


class FreeRoamStore:
    def __init__(self, data_dir, clock=None):
        # Keep the configured path: a missing directory must not turn into a new store.
        self.data_dir = Path(data_dir)
        self.clock = clock or (lambda: int(time.time() * 1000))

    def _connect(self):
        connection = None
        try:
            path = database_path(self.data_dir)
            connection = sqlite3.connect(path.as_uri() + "?mode=rw", uri=True, timeout=3)
            connection.row_factory = sqlite3.Row
            connection.execute("PRAGMA secure_delete = ON")
            if connection.execute("PRAGMA user_version").fetchone()[0] != SCHEMA_VERSION:
                raise StorageUnavailable("Unsupported Free Roam schema")
            if connection.execute("SELECT revision FROM settings WHERE singleton = 1").fetchone() is None:
                raise StorageUnavailable("Incomplete Free Roam storage")
            return connection
        except (OSError, sqlite3.Error, StorageUnavailable) as error:
            if connection is not None:
                connection.close()
            raise StorageUnavailable("Free Roam storage unavailable") from error

    @contextmanager
    def _transaction(self, write=False):
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            yield connection
            connection.commit()
        except sqlite3.Error as error:
            connection.rollback()
            raise StorageUnavailable("Free Roam storage unavailable") from error
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    @staticmethod
    def _settings(connection):
        row = connection.execute("SELECT * FROM settings WHERE singleton = 1").fetchone()
        try:
            controls = validate_controls(json.loads(row["controls"]))
        except (ValueError, TypeError, RecursionError) as error:
            raise StorageUnavailable("Invalid persisted controls") from error
        return row["revision"], controls

    @staticmethod
    def _profile(connection):
        row = connection.execute("SELECT * FROM profile WHERE singleton = 1").fetchone()
        return None if row is None else {
            "familiarId": row["familiar_id"], "pairingId": row["pairing_id"],
            "createdAt": row["creature_created_at"], "curiosity": row["curiosity"],
            "pairedAt": row["paired_at"],
        }

    def _check_revision(self, connection, expected):
        integer(expected, 0, 2**63 - 1)
        if self._profile(connection) is None:
            raise Conflict("Pair a familiar first")
        if expected != self._settings(connection)[0]:
            raise Conflict("State changed; fetch the current revision and try again")

    def _audit(self, connection, action, details):
        # Details contain IDs, counts, and field names, never note/goal text or credentials.
        connection.execute("UPDATE settings SET revision = revision + 1 WHERE singleton = 1")
        connection.execute("INSERT INTO audit(action, details, created_at) VALUES (?, ?, ?)",
                           (action, json.dumps(details), self.clock()))
        connection.execute("DELETE FROM audit WHERE sequence <= (SELECT MAX(sequence) FROM audit) - ?",
                           (MAX_AUDIT_ENTRIES,))
        return self._settings(connection)[0]

    @staticmethod
    def _memory(row):
        return {"sequence": row["sequence"], "id": row["id"], "kind": row["kind"],
                "topic": row["topic"], "text": row["text"], "createdAt": row["created_at"],
                "sources": json.loads(row["sources"])}

    @staticmethod
    def _interest(row):
        return {"id": row["id"], "topic": row["topic"], "priority": row["priority"],
                "createdAt": row["created_at"], "updatedAt": row["updated_at"]}

    @staticmethod
    def _goal(row):
        return {"id": row["id"], "topic": row["topic"], "question": row["question"],
                "status": row["status"], "priority": row["priority"],
                "createdAt": row["created_at"], "updatedAt": row["updated_at"]}

    @staticmethod
    def _event(row):
        return {"sequence": row["sequence"], "action": row["action"],
                "details": json.loads(row["details"]), "createdAt": row["created_at"]}

    @staticmethod
    def _run(row):
        return {"sequence": row["sequence"], "id": row["id"], "startedAt": row["started_at"],
                "finishedAt": row["finished_at"], "status": row["status"], "action": row["action"],
                "topic": row["topic"], "reason": row["reason"], "question": row["question"], "summary": row["summary"],
                "sources": json.loads(row["sources"]), "errorCode": row["error_code"],
                "modelCalls": row["calls"], "chargedMicrousd": row["charged_microusd"]}

    def _snapshot(self, connection):
        revision, controls = self._settings(connection)
        last = connection.execute("SELECT * FROM wake_runs ORDER BY sequence DESC LIMIT 1").fetchone()
        return {"revision": revision, "profile": self._profile(connection), "controls": controls,
                "runtime": dict(RUNTIME),
                "execution": {"lastWakeAt": None if last is None else last["started_at"],
                              "lastStatus": None if last is None else last["status"],
                              "nextWakeAt": None if last is None else last["started_at"] + controls["wakeIntervalHours"] * 3_600_000,
                              "monthlyCharges": [dict(row) for row in connection.execute(
                                  "SELECT month, charged_microusd AS chargedMicrousd FROM wake_budget ORDER BY month DESC LIMIT 12")]},
                "memoryCount": connection.execute("SELECT COUNT(*) FROM memories").fetchone()[0],
                "interests": [self._interest(row) for row in connection.execute("SELECT * FROM interests ORDER BY priority DESC, id")],
                "goals": [self._goal(row) for row in connection.execute("SELECT * FROM goals ORDER BY priority DESC, id")]}

    def snapshot(self):
        with self._transaction() as connection:
            return self._snapshot(connection)

    def pair(self, raw):
        fields(raw, ("pairingId", "createdAt", "curiosity"))
        pairing_id = identifier(raw["pairingId"])
        created_at = integer(raw["createdAt"], 0, 2**63 - 1)
        curiosity = raw["curiosity"]
        if type(curiosity) not in (int, float) or not 0 <= curiosity <= 1 or not math.isfinite(curiosity):
            raise ValueError("Invalid curiosity")
        with self._transaction(write=True) as connection:
            existing = self._profile(connection)
            if existing is not None:
                if (existing["pairingId"], existing["createdAt"], existing["curiosity"]) != (pairing_id, created_at, curiosity):
                    raise Conflict("This service is already paired; existing identity cannot be replaced")
                return self._snapshot(connection)
            connection.execute("INSERT INTO profile VALUES (1, ?, ?, ?, ?, ?)",
                               (str(uuid.uuid4()), pairing_id, created_at, curiosity, self.clock()))
            self._audit(connection, "paired", {})
            return self._snapshot(connection)

    def update_controls(self, raw):
        fields(raw, ("expectedRevision",), DEFAULT_CONTROLS)
        changes = {key: value for key, value in raw.items() if key != "expectedRevision"}
        if not changes:
            raise ValueError("Expected a control change")
        with self._transaction(write=True) as connection:
            self._check_revision(connection, raw["expectedRevision"])
            revision, controls = self._settings(connection)
            updated = validate_controls(controls | changes)
            if controls != updated:
                connection.execute("UPDATE settings SET controls = ? WHERE singleton = 1", (json.dumps(updated),))
                revision = self._audit(connection, "controls_updated", {"fields": sorted(changes)})
            return {"revision": revision, "controls": updated, "runtime": dict(RUNTIME)}

    def add_memory(self, raw):
        fields(raw, ("expectedRevision", "id", "topic", "text"))
        integer(raw["expectedRevision"], 0, 2**63 - 1)
        memory_id, topic, content = identifier(raw["id"]), text(raw["topic"], 80), text(raw["text"], 2000, multiline=True)
        with self._transaction(write=True) as connection:
            existing = connection.execute("SELECT * FROM memories WHERE id = ?", (memory_id,)).fetchone()
            if existing is not None and (existing["topic"], existing["text"]) == (topic, content):
                return {"revision": self._settings(connection)[0], "memory": self._memory(existing)}
            self._check_revision(connection, raw["expectedRevision"])
            if existing is not None:
                raise Conflict("A memory ID cannot be reused for different content")
            if connection.execute("SELECT COUNT(*) FROM memories").fetchone()[0] >= MAX_MEMORIES:
                raise Conflict("Memory limit reached; export or forget notes before adding more")
            connection.execute("INSERT INTO memories(id, topic, text, created_at) VALUES (?, ?, ?, ?)",
                               (memory_id, topic, content, self.clock()))
            revision = self._audit(connection, "memory_added", {"id": memory_id})
            row = connection.execute("SELECT * FROM memories WHERE id = ?", (memory_id,)).fetchone()
            return {"revision": revision, "memory": self._memory(row)}

    def save_interest(self, raw):
        fields(raw, ("expectedRevision", "id", "topic", "priority"))
        entry_id, topic = identifier(raw["id"]), text(raw["topic"], 80)
        priority = integer(raw["priority"], 1, 5)
        with self._transaction(write=True) as connection:
            self._check_revision(connection, raw["expectedRevision"])
            existing = connection.execute("SELECT * FROM interests WHERE id = ?", (entry_id,)).fetchone()
            duplicate = connection.execute("SELECT id FROM interests WHERE topic_key = ?", (topic.casefold(),)).fetchone()
            if duplicate is not None and duplicate["id"] != entry_id:
                raise Conflict("This interest already exists")
            if existing is None and connection.execute("SELECT COUNT(*) FROM interests").fetchone()[0] >= MAX_INTERESTS:
                raise Conflict("Keep at most ten interests")
            now = self.clock()
            connection.execute("""INSERT INTO interests VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET topic=excluded.topic, topic_key=excluded.topic_key,
                priority=excluded.priority, updated_at=excluded.updated_at""",
                (entry_id, topic, topic.casefold(), priority, now, now))
            revision = self._audit(connection, "interest_saved", {"id": entry_id})
            row = connection.execute("SELECT * FROM interests WHERE id = ?", (entry_id,)).fetchone()
            return {"revision": revision, "interest": self._interest(row)}

    def save_goal(self, raw):
        fields(raw, ("expectedRevision", "id", "topic", "question", "status", "priority"))
        entry_id, topic = identifier(raw["id"]), text(raw["topic"], 80)
        question, priority = text(raw["question"], 500), integer(raw["priority"], 1, 5)
        status = raw["status"]
        if status not in ("active", "paused", "completed"):
            raise ValueError("Invalid goal status")
        with self._transaction(write=True) as connection:
            self._check_revision(connection, raw["expectedRevision"])
            existing = connection.execute("SELECT id FROM goals WHERE id = ?", (entry_id,)).fetchone()
            if existing is None and connection.execute("SELECT COUNT(*) FROM goals").fetchone()[0] >= MAX_GOALS:
                raise Conflict("Goal history limit reached; export or remove old goals first")
            active_others = connection.execute("SELECT COUNT(*) FROM goals WHERE status = 'active' AND id != ?", (entry_id,)).fetchone()[0]
            if status == "active" and active_others >= MAX_ACTIVE_GOALS:
                raise Conflict("Keep at most three active goals")
            now = self.clock()
            connection.execute("""INSERT INTO goals VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET topic=excluded.topic, question=excluded.question,
                status=excluded.status, priority=excluded.priority, updated_at=excluded.updated_at""",
                (entry_id, topic, question, status, priority, now, now))
            revision = self._audit(connection, "goal_saved", {"id": entry_id})
            row = connection.execute("SELECT * FROM goals WHERE id = ?", (entry_id,)).fetchone()
            return {"revision": revision, "goal": self._goal(row)}

    def delete_entry(self, collection, entry_id, raw):
        fields(raw, ("expectedRevision",))
        identifier(entry_id)
        if collection not in ("memories", "interests", "goals", "journal"):
            raise ValueError("Unknown collection")
        with self._transaction(write=True) as connection:
            self._check_revision(connection, raw["expectedRevision"])
            # Table names come exclusively from the allowlist above; values are bound.
            if collection == "journal":
                deleted = connection.execute("UPDATE wake_runs SET visible = 0 WHERE id = ? AND visible = 1", (entry_id,)).rowcount
                connection.execute("DELETE FROM memories WHERE id = ? AND kind != 'owner_note'", (entry_id,))
            else:
                deleted = connection.execute(f"DELETE FROM {collection} WHERE id = ?", (entry_id,)).rowcount
            if deleted and collection in ("memories", "journal"):
                self._redact_runs(connection, "id = ?", (entry_id,))
            revision = self._settings(connection)[0]
            if deleted:
                revision = self._audit(connection, "entry_forgotten", {"collection": collection, "id": entry_id})
            return {"revision": revision, "deleted": bool(deleted)}

    def page(self, collection, after=0, limit=20):
        integer(after, 0, 2**63 - 1)
        integer(limit, 1, 50)
        if collection not in ("memories", "audit", "journal"):
            raise ValueError("Unknown collection")
        with self._transaction() as connection:
            table, condition = ("wake_runs", "visible = 1 AND ") if collection == "journal" else (collection, "")
            rows = connection.execute(f"SELECT * FROM {table} WHERE {condition}sequence > ? ORDER BY sequence LIMIT ?",
                                      (after, limit + 1)).fetchall()
            convert = {"memories": self._memory, "audit": self._event, "journal": self._run}[collection]
            items = [convert(row) for row in rows[:limit]]
            return {"revision": self._settings(connection)[0], "items": items,
                    "nextCursor": items[-1]["sequence"] if len(rows) > limit else None}

    def export(self):
        with self._transaction() as connection:
            return self._snapshot(connection) | {
                "schemaVersion": SCHEMA_VERSION, "exportedAt": self.clock(),
                "memories": [self._memory(row) for row in connection.execute("SELECT * FROM memories ORDER BY sequence")],
                "audit": [self._event(row) for row in connection.execute("SELECT * FROM audit ORDER BY sequence")],
                "journal": [self._run(row) for row in connection.execute("SELECT * FROM wake_runs WHERE visible = 1 ORDER BY sequence")],
            }

    def forget(self, raw):
        fields(raw, ("expectedRevision", "confirmation"))
        if raw["confirmation"] != "forget-cognitive-memory":
            raise ValueError("Explicit confirmation required")
        with self._transaction(write=True) as connection:
            self._check_revision(connection, raw["expectedRevision"])
            for table in ("memories", "interests", "goals", "audit"):
                connection.execute(f"DELETE FROM {table}")
            self._redact_runs(connection, "1 = 1", ())
            controls = self._settings(connection)[1] | {"enabled": False, "webEnabled": False}
            connection.execute("UPDATE settings SET controls = ? WHERE singleton = 1", (json.dumps(controls),))
            self._audit(connection, "cognitive_memory_forgotten", {})
            return self._snapshot(connection)

    @staticmethod
    def _redact_runs(connection, predicate, parameters):
        # Keep content-free receipts: forgetting must not reset spending or scheduling.
        connection.execute(f"""UPDATE wake_runs SET visible = 0, action = NULL, topic = NULL,
            reason = NULL, question = NULL, summary = NULL, sources = '[]', error_code = NULL,
            status = CASE WHEN status = 'running' THEN 'cancelled' ELSE status END
            WHERE {predicate}""", parameters)

    def claim_wake(self):
        """Atomically claim one due wake and reserve its worst-case cost. Never replay it."""
        now = self.clock()
        date = datetime.fromtimestamp(now / 1000, timezone.utc)
        day, month = date.strftime("%Y-%m-%d"), date.strftime("%Y-%m")
        with self._transaction(write=True) as connection:
            # A lost worker remains charged its full reservation, even after restart.
            expired = connection.execute("""UPDATE wake_runs SET status = 'interrupted', finished_at = ?,
                error_code = 'worker_lost' WHERE status = 'running' AND started_at <= ?""",
                (now, now - WAKE_LEASE_MS)).rowcount
            if expired:
                self._audit(connection, "wake_interrupted", {"count": expired})
            revision, controls = self._settings(connection)
            if not controls["enabled"] or self._profile(connection) is None:
                return None
            last = connection.execute("SELECT * FROM wake_runs ORDER BY sequence DESC LIMIT 1").fetchone()
            if last is not None and (last["status"] == "running" or now < last["started_at"] + controls["wakeIntervalHours"] * 3_600_000):
                return None
            if connection.execute("SELECT COUNT(*) FROM wake_runs WHERE day = ?", (day,)).fetchone()[0] >= controls["dailyWakeLimit"]:
                return None
            if (connection.execute("SELECT COUNT(*) FROM memories").fetchone()[0] >= MAX_MEMORIES or
                    connection.execute("SELECT COUNT(*) FROM wake_runs").fetchone()[0] >= MAX_WAKE_RUNS):
                return None
            row = connection.execute("SELECT charged_microusd FROM wake_budget WHERE month = ?", (month,)).fetchone()
            spent = 0 if row is None else row[0]
            if spent + WAKE_RESERVATION_MICROUSD > controls["monthlyBudgetCents"] * 10_000:
                return None
            run_id = str(uuid.uuid4())
            revision = self._audit(connection, "wake_started", {"id": run_id})
            connection.execute("""INSERT INTO wake_runs(id, started_at, day, month, status, revision, charged_microusd)
                VALUES (?, ?, ?, ?, 'running', ?, ?)""", (run_id, now, day, month, revision, WAKE_RESERVATION_MICROUSD))
            connection.execute("""INSERT INTO wake_budget VALUES (?, ?) ON CONFLICT(month)
                DO UPDATE SET charged_microusd = charged_microusd + excluded.charged_microusd""", (month, WAKE_RESERVATION_MICROUSD))
            context = {"curiosity": self._profile(connection)["curiosity"],
                       "interests": [self._interest(row) for row in connection.execute("SELECT * FROM interests ORDER BY priority DESC LIMIT 10")],
                       "goals": [self._goal(row) for row in connection.execute("SELECT * FROM goals WHERE status = 'active' ORDER BY priority DESC LIMIT 3")],
                       "memories": [self._memory(row) for row in connection.execute("SELECT * FROM memories ORDER BY sequence DESC LIMIT 8")],
                       "recentJournal": [self._run(row) for row in connection.execute("SELECT * FROM wake_runs WHERE visible = 1 AND status != 'running' ORDER BY sequence DESC LIMIT 4")]}
            # Explicit projection keeps timestamps, identity IDs, and accounting off the provider.
            context["interests"] = [{key: item[key] for key in ("topic", "priority")} for item in context["interests"]]
            context["goals"] = [{key: item[key] for key in ("topic", "question", "priority")} for item in context["goals"]]
            context["memories"] = [{key: item[key] for key in ("kind", "topic", "text", "sources")} for item in context["memories"]]
            context["recentJournal"] = [{key: item[key] for key in ("status", "action", "topic", "question", "summary")} for item in context["recentJournal"]]
            return {"id": run_id, "revision": revision, "controls": controls, "context": context}

    def begin_wake_call(self, claim, web=False):
        """Recheck consent at every call boundary; owner edits invalidate old context."""
        with self._transaction(write=True) as connection:
            row = connection.execute("SELECT * FROM wake_runs WHERE id = ?", (claim["id"],)).fetchone()
            revision, controls = self._settings(connection)
            if (row is None or row["status"] != "running" or revision != claim["revision"] or row["revision"] != claim["revision"] or
                    not controls["enabled"] or self.clock() >= row["started_at"] + WAKE_LEASE_MS or
                    row["calls"] >= 2 or row["metered_microusd"] >= WAKE_RESERVATION_MICROUSD or
                    (web and (not controls["webEnabled"] or not controls["allowedDomains"]))):
                return False
            connection.execute("UPDATE wake_runs SET calls = calls + 1 WHERE id = ?", (claim["id"],))
            return True

    def record_wake_decision(self, claim, decision):
        with self._transaction(write=True) as connection:
            if self._settings(connection)[0] != claim["revision"]:
                return False
            return bool(connection.execute("""UPDATE wake_runs SET action = ?, topic = ?, reason = ?, question = ?
                WHERE id = ? AND status = 'running' AND visible = 1""",
                (decision["action"], decision["topic"], decision["reason"], decision["question"], claim["id"])).rowcount)

    def meter_wake_call(self, claim, cost):
        integer(cost, 0, 2**63 - 1)
        with self._transaction(write=True) as connection:
            connection.execute("UPDATE wake_runs SET metered_microusd = metered_microusd + ? WHERE id = ?", (cost, claim["id"]))

    def finish_wake(self, claim, outcome=None, error_code=None, usage_known=True):
        """Persist only a still-authorized result, and settle cost even after cancellation."""
        with self._transaction(write=True) as connection:
            row = connection.execute("SELECT * FROM wake_runs WHERE id = ?", (claim["id"],)).fetchone()
            if row is None or row["status"] not in ("running", "cancelled") or row["finished_at"] is not None:
                return
            revision, controls = self._settings(connection)
            valid = (row["status"] == "running" and row["visible"] and controls["enabled"] and
                     revision == claim["revision"] == row["revision"] and self.clock() < row["started_at"] + WAKE_LEASE_MS)
            cost = row["metered_microusd"] if usage_known else max(row["metered_microusd"], WAKE_RESERVATION_MICROUSD)
            connection.execute("UPDATE wake_budget SET charged_microusd = charged_microusd + ? WHERE month = ?",
                               (cost - row["charged_microusd"], row["month"]))
            status = "cancelled" if not valid else "failed" if error_code else "completed"
            connection.execute("UPDATE wake_runs SET status = ?, finished_at = ?, charged_microusd = ?, error_code = ? WHERE id = ?",
                               (status, self.clock(), cost, error_code if valid else None, claim["id"]))
            if cost > WAKE_RESERVATION_MICROUSD:
                connection.execute("UPDATE settings SET controls = ? WHERE singleton = 1", (json.dumps(controls | {"enabled": False}),))
                self._audit(connection, "wake_budget_overrun", {"id": claim["id"]})
            if valid and outcome is not None and not error_code:
                connection.execute("UPDATE wake_runs SET action = ?, topic = ?, reason = ?, summary = ?, sources = ? WHERE id = ?",
                    (outcome["action"], outcome["topic"], outcome["reason"], outcome["summary"], json.dumps(outcome["sources"]), claim["id"]))
                if outcome["action"] != "rest":
                    kind = "finding" if outcome["sources"] else "reflection"
                    connection.execute("INSERT INTO memories(id, topic, text, created_at, kind, sources) VALUES (?, ?, ?, ?, ?, ?)",
                        (claim["id"], outcome["topic"], outcome["summary"], self.clock(), kind, json.dumps(outcome["sources"])))
            self._audit(connection, "wake_finished", {"id": claim["id"], "status": status})
            return status

    def backup(self, destination):
        target = Path(destination)
        if not target.is_absolute() or not target.parent.is_dir():
            raise ValueError("Use an absolute backup path in an existing directory")
        # Refuse to overwrite either the live store or an earlier backup.
        with closing(self._connect()) as source:
            descriptor = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(descriptor)
            with closing(sqlite3.connect(target)) as copy:
                source.backup(copy)


def store_from_environment():
    directory = os.environ.get("FAMILIAR_DATA_DIR", "").strip()
    if not directory:
        return None
    # No fallback to the container filesystem, and no implicit initialization.
    return FreeRoamStore(directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "backup", "migrate"))
    parser.add_argument("--data-dir", required=True)
    parser.add_argument("--output")
    args = parser.parse_args()
    try:
        if args.command == "init":
            initialize(args.data_dir)
            print("Free Roam storage initialized; autonomous execution is disabled by default.")
        elif args.command == "migrate" and args.output:
            migrate(args.data_dir, args.output)
            print("Free Roam storage upgraded; existing identity and content preserved.")
        elif args.output:
            FreeRoamStore(args.data_dir).backup(args.output)
            print("Free Roam SQLite backup saved.")
        else:
            parser.error("backup and migrate require --output")
    except (OSError, sqlite3.Error, ValueError, StorageUnavailable):
        parser.exit(1, "Free Roam storage operation failed; check the path, permissions, and schema.\n")


if __name__ == "__main__":
    main()
