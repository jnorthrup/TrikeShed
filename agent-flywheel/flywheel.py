#!/usr/bin/env python3
"""
agent-flywheel: a generic, repo-agnostic continuous coding flywheel.

Cycle: research → triage → rank → dispatch → tend → harvest → land → persist.

Zero knowledge of any specific repo. The brain reads snapshot.sh output;
land.py applies and tests the patch; state.json survives restarts.
"""
from __future__ import annotations
import argparse, hashlib, heapq, itertools, json, os, re, subprocess, sys
import time, urllib.error, urllib.parse, urllib.request
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone

JULES_BASE = "https://jules.googleapis.com/v1alpha"
BRAIN_BASE = os.environ.get("BRAIN_BASE", "https://integrate.api.nvidia.com/v1")
BRAIN_MODEL = os.environ.get("BRAIN_MODEL", "poolside/laguna-xs-2.1")
JULES_KEY = os.environ["JULES_API_KEY"]
NVIDIA_KEY = os.environ.get("NVIDIA_API_KEY", "")
JULES_SOURCE = os.environ["JULES_SOURCE"]
REPO_PATH = os.environ.get("REPO_PATH", ".")
MAIN_BRANCH = os.environ.get("FLYWHEEL_MAIN", "")
MAX_LIVE = int(os.environ.get("MAX_LIVE", "5"))
POLL_INTERVAL = int(os.environ.get("POLL_INTERVAL", "20"))
STATE_PATH = os.environ.get("FLYWHEEL_STATE",
                            os.path.join(REPO_PATH, ".flywheel.state.json"))

def http(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("x-goog-api-key", JULES_KEY)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = r.read()
    return json.loads(raw) if raw else {}

def brain_chat(system, user, model=BRAIN_MODEL, max_tokens=8192, temperature=1.0):
    base = BRAIN_BASE if model == BRAIN_MODEL else "https://api.openai.com/v1"
    key = NVIDIA_KEY if model == BRAIN_MODEL else os.environ.get("OPENAI_API_KEY", "")
    req = urllib.request.Request(f"{base}/chat/completions",
        data=json.dumps({"model": model,
                          "messages": [{"role": "system", "content": system},
                                       {"role": "user", "content": user}],
                          "temperature": temperature,
                          "max_tokens": max_tokens}).encode(), method="POST")
    req.add_header("Authorization", f"Bearer {key}")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            data = json.loads(r.read())
        return data["choices"][0]["message"]["content"] or ""
    except Exception as e:
        return f"__BRAIN_ERROR__: {e}"

def brain_json(system, user, default):
    for _ in range(2):
        txt = brain_chat(system + "\nRespond ONLY with valid JSON.", user,
                         temperature=0.2)
        m = re.search(r"\{.*\}|\[.*\]", txt, re.S)
        if not m:
            continue
        try:
            return json.loads(m.group(0))
        except Exception:
            continue
    return default

def proposal_list(value):
    if isinstance(value, list):
        return value
    if isinstance(value, dict):
        for key in ("tasks", "items", "proposals"):
            if isinstance(value.get(key), list):
                return value[key]
    return []

class Jules:
    @staticmethod
    def create(prompt, title):
        body = {"prompt": prompt, "title": title[:80],
                "sourceContext": {"source": JULES_SOURCE,
                                   "githubRepoContext": {"startingBranch": _main()}},
                "requirePlanApproval": True}
        return http("POST", f"{JULES_BASE}/sessions", body)
    @staticmethod
    def get(name): return http("GET", f"{JULES_BASE}/{name}")
    @staticmethod
    def sessions():
        sessions = []
        page_token = ""
        while True:
            url = f"{JULES_BASE}/sessions?pageSize=100"
            if page_token:
                url += f"&pageToken={urllib.parse.quote(page_token)}"
            page = http("GET", url)
            sessions.extend(page.get("sessions", []))
            page_token = str(page.get("nextPageToken", ""))
            if not page_token:
                return sessions
    @staticmethod
    def activities(name, after_ts=None):
        base_url = f"{JULES_BASE}/{name}/activities?pageSize=50"
        activities = []
        page_token = ""
        while True:
            url = base_url
            if page_token:
                url += f"&pageToken={urllib.parse.quote(page_token)}"
            page = http("GET", url)
            activities.extend(page.get("activities", []))
            page_token = str(page.get("nextPageToken", ""))
            if not page_token:
                break
        if after_ts:
            activities = [a for a in activities
                          if str(a.get("createTime", "")) > after_ts]
        return activities
    @staticmethod
    def send(name, msg): http("POST", f"{JULES_BASE}/{name}:sendMessage", {"prompt": msg})
    @staticmethod
    def approve(name): http("POST", f"{JULES_BASE}/{name}:approvePlan", {})
    @staticmethod
    def delete(name):
        try: http("DELETE", f"{JULES_BASE}/{name}")
        except Exception: pass

def _main():
    global MAIN_BRANCH
    if MAIN_BRANCH: return MAIN_BRANCH
    for c in ("main", "master", "trunk"):
        p = subprocess.run(["git", "-C", REPO_PATH, "rev-parse", "--verify", c],
                           capture_output=True, text=True)
        if p.returncode == 0:
            MAIN_BRANCH = c; return c
    MAIN_BRANCH = "main"; return "main"

def fit_snapshot(out, limit=16000):
    if len(out) <= limit:
        return out
    marker = "## doc/ work pool"
    pool_at = out.find(marker)
    if pool_at < 0:
        return out[:limit]
    pool = out[pool_at:]
    separator = "\n\n[earlier snapshot content truncated]\n\n"
    prefix_size = max(0, limit - len(pool) - len(separator))
    return (out[:prefix_size] + separator + pool)[:limit]

def snapshot():
    custom = os.environ.get("FLYWHEEL_SNAPSHOT")
    script = custom or os.path.join(os.path.dirname(__file__) or ".", "snapshot.sh")
    p = subprocess.run([script, REPO_PATH], capture_output=True, text=True)
    out = (p.stdout or "")
    return fit_snapshot(out)

TIER = {"epic": 0, "feature": 1, "task": 2, "test": 3, "chore": 4}
_seq = itertools.count()

@dataclass(order=True)
class WorkItem:
    sort_key: tuple = field(init=False, repr=False, compare=True)
    tier: str = "task"; score: float = 0.5; title: str = ""; spec: str = ""
    parent: str | None = None
    attempt: int = field(default=0, compare=False)
    feedback: str = field(default="", compare=False)
    def __post_init__(self):
        h = hashlib.sha1()
        h.update(self.tier.encode()); h.update(b"|")
        h.update((self.parent or "").encode()); h.update(b"|")
        h.update(self.title.encode()); h.update(b"|")
        h.update(self.spec.encode())
        self.fingerprint = h.hexdigest()[:12]
        self.sort_key = (TIER.get(self.tier, 4), 1.0 - self.score, next(_seq))

class WorkPQ:
    def __init__(self):
        self._h = []; self._seen = set()
    def push(self, w):
        if w.fingerprint in self._seen: return
        self._seen.add(w.fingerprint); heapq.heappush(self._h, w)
    def pop(self):
        if not self._h:
            return None
        work = heapq.heappop(self._h)
        self._seen.discard(work.fingerprint)
        return work
    def __len__(self): return len(self._h)
    def __iter__(self): return iter(sorted(self._h))

def task_key(title):
    title = str(title).strip()
    match = re.match(
        r"^(T\d+[A-Z]?|ORO-\d+|GATE-[A-Z0-9-]+|T-[A-Z0-9-]+)",
        title.upper(),
    )
    if match:
        return match.group(1)
    return re.sub(r"[^a-z0-9]+", " ", title.lower()).strip()

def unseen_proposals(proposals, pq, live, outcomes=()):
    known_titles = {task_key(w.title) for w in pq}
    known_titles.update(
        task_key(s.get("work", {}).get("title", "")) for s in live.values()
    )
    known_titles.update(
        task_key(outcome.get("title", ""))
        for outcome in outcomes
        if outcome.get("ok")
    )
    unseen = []
    for proposal in proposals:
        title = str(proposal.get("title", "")) if isinstance(proposal, dict) else ""
        key = task_key(title)
        if not key or key in known_titles:
            continue
        unseen.append(proposal)
        known_titles.add(key)
    return unseen

def adopt_active_sessions(live, sessions, harvested=None):
    harvested = harvested if harvested is not None else set()
    adopted = []
    for session in sessions:
        name = str(session.get("name", ""))
        state = str(session.get("state", ""))
        source = str(session.get("sourceContext", {}).get("source", ""))
        is_terminal = state in ("COMPLETED", "FINISHED")
        harvestable = (state in ("QUEUED", "PLANNING", "IN_PROGRESS",
                                 "COMPLETED", "FINISHED")
                       or state.startswith("AWAITING_"))
        if (not name or name in live or name in harvested
                or source != JULES_SOURCE or not harvestable):
            continue
        # Cap active (non-terminal) adoption at MAX_LIVE so the live map
        # doesn't bloat to 47 sessions and stall the serial harvest loop.
        # Terminal sessions are always adopted — they need harvesting to
        # free dispatch slots.
        if not is_terminal and len(live) >= MAX_LIVE:
            continue
        title = str(session.get("title") or f"adopted Jules session {name.split('/')[-1]}")
        spec = str(session.get("prompt") or title)
        work = WorkItem(tier="task", score=0.5, title=title, spec=spec)
        live[name] = {
            "work": {"tier": work.tier, "score": work.score,
                     "title": work.title, "spec": work.spec,
                     "parent": work.parent, "fingerprint": work.fingerprint,
                     "attempt": 0, "feedback": "adopted from live Jules source"},
            "state": state, "last_poll": None, "patches": [],
            "last_question": None, "answer_count": 0, "adopted": True,
            "url": str(session.get("url") or
                       f"https://jules.google.com/task/{name.split('/')[-1]}")}
        adopted.append(name)
    return adopted

def load_state():
    pq = WorkPQ(); live = {}; landed = 0; outcomes = deque(maxlen=50)
    harvested = set()
    if not os.path.exists(STATE_PATH): return pq, live, landed, outcomes, harvested
    try:
        with open(STATE_PATH) as f:
            data = json.load(f)
    except Exception: return pq, live, landed, outcomes, harvested
    for w in data.get("queue", []):
        pq.push(WorkItem(tier=w.get("tier","task"), score=float(w.get("score",0.5)),
                         title=w.get("title",""), spec=w.get("spec",""),
                         parent=w.get("parent"), attempt=int(w.get("attempt", 0)),
                         feedback=str(w.get("feedback", ""))))
    live.update(data.get("live", {}))
    landed = int(data.get("landed", 0))
    for o in data.get("outcomes", []): outcomes.append(o)
    for name in data.get("harvested", []): harvested.add(str(name))
    return pq, live, landed, outcomes, harvested

def save_state(pq, live, landed, outcomes, harvested=None):
    harvested = harvested if harvested is not None else set()
    tmp = STATE_PATH + ".tmp"
    payload = {"queue": [{"tier": w.tier, "score": w.score, "title": w.title,
                          "spec": w.spec, "parent": w.parent,
                          "attempt": w.attempt, "feedback": w.feedback} for w in pq],
               "live": live, "landed": landed, "outcomes": list(outcomes),
               "harvested": sorted(harvested),
               "saved_at": datetime.now(timezone.utc).isoformat()}
    with open(tmp, "w") as f: json.dump(payload, f, indent=2)
    os.replace(tmp, STATE_PATH)

def retry_work(sess, reason, patch_text=""):
    work = sess.get("work", {})
    attempt = int(work.get("attempt", 0)) + 1
    fingerprint = work.get("fingerprint") or hashlib.sha1(
        (work.get("title", "") + "|" + work.get("spec", "")).encode()
    ).hexdigest()[:12]
    artifact = ""
    if patch_text:
        failure_dir = os.path.join(os.path.dirname(STATE_PATH) or ".",
                                   ".flywheel-failures")
        os.makedirs(failure_dir, exist_ok=True)
        artifact = os.path.join(failure_dir,
                                f"{fingerprint}-attempt-{attempt}.patch")
        with open(artifact, "w") as f:
            f.write(patch_text)
        with open(artifact + ".json", "w") as f:
            json.dump({"title": work.get("title", ""), "attempt": attempt,
                       "reason": reason,
                       "saved_at": datetime.now(timezone.utc).isoformat()}, f, indent=2)
    feedback = f"Attempt {attempt} must resolve this gate failure:\n{reason.strip()}"
    if artifact:
        feedback += (f"\nThe complete rejected patch is preserved at {artifact}."
                     f"\nRejected patch:\n{patch_text[:50000]}")
    return WorkItem(tier=str(work.get("tier", "task")),
                    score=float(work.get("score", 0.5)),
                    title=str(work.get("title", "untitled")),
                    spec=str(work.get("spec", "")), parent=work.get("parent"),
                    attempt=attempt, feedback=feedback)

def dispatch_available(pq, live, cycle):
    while len(live) < MAX_LIVE and len(pq) > 0:
        w = pq.pop()
        if not w:
            break
        repair = ""
        if w.feedback:
            repair = (f"\n\nCONTINUOUS REWORK ATTEMPT {w.attempt}: The prior "
                      f"candidate did not fit. Do not abandon the task. Resolve "
                      f"the conflict or gate failure against current HEAD and emit "
                      f"a new cumulative patch.\n{w.feedback}")
        spec = (f"{w.spec}{repair}\n\n"
                f"RULES:\n"
                f"1. TDD strictly — write failing tests first, then implement to green.\n"
                f"2. Run ./gradlew jvmTest yourself before finishing. Your patch MUST\n"
                f"   produce a green jvmTest. If tests fail, fix them before emitting\n"
                f"   the final patch. Do not emit a patch that has failing tests.\n"
                f"3. Keep the diff minimal: one test file + one implementation file.\n"
                f"4. Do NOT open a PR. Produce a final cumulative patch/changeset only.\n"
                f"5. Reply with KEEP_GOING only if you have a genuine blocking question.\n"
                f"6. Use platform.posix not jnr-posix. Domain in commonMain, JVM in jvmMain.\n"
                f"7. Series<T> over List<T>. Confix is the only serializer.\n")
        try:
            s = Jules.create(spec, w.title)
        except Exception as e:
            detail = e.read().decode()[:200] if isinstance(
                e, urllib.error.HTTPError
            ) else str(e)[:200]
            print(f"  dispatch failed: {detail}", flush=True)
            pq.push(w)
            break
        sid = s["name"]
        live[sid] = {
            "work": {"tier": w.tier, "score": w.score, "title": w.title,
                     "spec": w.spec, "parent": w.parent,
                     "fingerprint": w.fingerprint, "attempt": w.attempt,
                     "feedback": w.feedback},
            "state": s.get("state", "QUEUED"), "last_poll": None,
            "patches": [], "last_question": None, "answer_count": 0,
            "url": f"https://jules.google.com/task/{sid.split('/')[-1]}"}
        print(f"  cycle {cycle}: dispatched [{w.tier}] {w.title} "
              f"attempt={w.attempt}", flush=True)

def _extract_question(act):
    if "agentMessaged" in act:
        m = act["agentMessaged"]
        return m.get("agentMessage") or m.get("message")
    if "progressUpdated" in act:
        pu = act["progressUpdated"]
        if isinstance(pu, dict):
            text = (pu.get("title","") + " " + pu.get("message","")).strip()
            if "?" in text: return text
    return None

def latest_question(activities):
    explicit = [_extract_question(activity) for activity in activities
                if "agentMessaged" in activity]
    explicit = [question for question in explicit if question]
    if explicit:
        return explicit[-1]
    for activity in reversed(activities):
        question = _extract_question(activity)
        if question:
            return question
    return None

def confirmation_loop(state, answer_count, question, last_question):
    return (state == "AWAITING_USER_FEEDBACK" and answer_count >= 2
            and bool(question) and question != last_question)

def should_answer_question(state, question, last_question):
    return (state == "AWAITING_USER_FEEDBACK" and bool(question)
            and question != last_question)

def session_interruption_reason(state):
    if state in ("FAILED", "PAUSED"):
        return f"Jules session {state.lower()} before producing a landable patch"
    return None

def merge_receipt(settlement):
    return "\n".join([
        "TRIKESHED DRAIN RECEIPT",
        "drainTarget: TrikeShed",
        "drainStatus: MERGED",
        f"drainDate: {settlement['drainDate']}",
        f"parentSha: {settlement['parentSha']}",
        f"mergeSha: {settlement['mergeSha']}",
        f"commitSha: {settlement['commitSha']}",
        "source: origin/master",
    ])

def inform_merged_session(name, settlement):
    receipt = merge_receipt(settlement)
    error = None
    for attempt in range(3):
        try:
            Jules.send(name, receipt)
            return True, None
        except Exception as exc:
            error = str(exc)
            time.sleep(2 ** attempt)
    return False, error

def mark_drained(title, session_name):
    """After a Jules merge, mark the task done in doc/todo.md.
    The Jules URL task ID is the object ID, stripped of REST prefix."""
    task_id = session_name.split("/")[-1]
    todo_path = os.path.join(REPO_PATH, "doc", "todo.md")
    if not os.path.exists(todo_path):
        return
    try:
        with open(todo_path) as f:
            lines = f.readlines()
    except Exception:
        return
    changed = False
    for i, line in enumerate(lines):
        if "- [ ]" in line and title[:40].lower() in line.lower():
            indent = line[:line.index("- [ ]")]
            rest = line[line.index("] ") + 2:].rstrip()
            # Strip trailing markdown bold/parens for clean drain marker
            lines[i] = f"{indent}- [x] {rest} — DRAINED {task_id}\n"
            changed = True
            break
    if changed:
        with open(todo_path, "w") as f:
            f.writelines(lines)
        try:
            subprocess.run(["git", "-C", REPO_PATH, "add", "doc/todo.md"],
                           capture_output=True, text=True, timeout=10)
            subprocess.run(["git", "-C", REPO_PATH, "commit", "-m",
                           f"drain: {title[:60]} ({task_id})"],
                           capture_output=True, text=True, timeout=10)
            subprocess.run(["git", "-C", REPO_PATH, "push", "origin", MAIN_BRANCH],
                           capture_output=True, text=True, timeout=30)
        except Exception:
            pass

def change_set_patch(change_set):
    patch = change_set.get("gitPatch", "") if isinstance(change_set, dict) else ""
    if isinstance(patch, str):
        return patch
    if isinstance(patch, dict):
        nested = patch.get("unidiffPatch", "")
        return nested if isinstance(nested, str) else ""
    return ""

def client_patch(name):
    session_id = name.split("/")[-1]
    command = [os.environ.get("JULES_CLI", "jules"), "remote", "pull",
               "--session", session_id]
    try:
        result = subprocess.run(command, cwd=REPO_PATH, capture_output=True,
                                text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as e:
        print(f"  Jules client pull failed {name}: {e}", flush=True)
        return ""
    if result.returncode != 0:
        print(f"  Jules client pull failed {name}: {result.stderr[:300]}",
              flush=True)
        return ""
    patch_at = result.stdout.find("diff --git ")
    return result.stdout[patch_at:] if patch_at >= 0 else ""

RESEARCHER = (
    "You are the research spoke of a generic dev flywheel. The repo snapshot "
    "includes a 'doc/ work pool' section listing unchecked [ ] task items — "
    "these are the project's own prioritized work queue. Your job is to pick "
    "from THAT pool, not invent new tasks. Read the unchecked items, pick the "
    "highest-leverage ones, and turn each into a complete WorkItem spec.\n\n"
    "Output a JSON list of objects, each exactly:\n"
    '{"tier":"epic|feature|task|test|chore","title":"...","score":0..1,'
    '"spec":"a TDD-first prompt for a coding agent: write failing tests '
    'FIRST, then implement until green, keep the diff minimal and '
    'self-contained. Do NOT open a PR; produce a final patch only. '
    'The title MUST match an unchecked item from the work pool."}\n'
    "Limit to at most 5 items. Pick from the work pool; do NOT invent tasks "
    "that are not in the pool.")
TRIAGER = ("You filter coding-agent proposals. Reject merge-conflict-prone, "
           "duplicate, or vague items. Output JSON "
           '{"keep": [indices], "reason": "..."} Keep at most half.')
ANSWERER = ("You answer a coding agent's inquiry on behalf of the project "
            "owner. Be decisive, concrete, unblockingly specific. "
            "Answer only the concrete inquiry, point by point. "
            "Never send KEEP_GOING or a generic nudge.\n\n"
            "PROJECT CONVENTIONS (TrikeShed KMP):\n"
            "- Domain logic lives in commonMain. JVM-specific code (java.nio, "
            "JNR, JMH, Bouncycastle) lives in jvmMain only.\n"
            "- Use platform.posix (Kotlin/Native) for POSIX, NOT jnr-posix. "
            "jnr-posix is JVM-only and forbidden in commonMain.\n"
            "- No kotlinx-serialization-json or kotlinx-serialization-cbor in "
            "commonMain. Confix is the only serialization format.\n"
            "- Inheritance/shared intermediate source sets over expect/actual. "
            "Use expect/actual only for platform SPI.\n"
            "- Series<T> (borg.trikeshed.lib) over List<T>. Use the alpha "
            "operator (it). Avoid .view.toList() materialization.\n"
            "- TDD strictly: write the failing test first, then implement.\n"
            "- Keep diffs minimal. One test file + one implementation file.\n"
            "- If a symbol the test references does not exist, create the "
            "minimal production type that makes the test compile, then "
            "implement to green. Do not rename the test's API.\n"
            "- No mocks, fakes, or stubs in production code. If a dependency "
            "is unavailable, use an interface and inject a real implementation.\n"
            "- Test files go in commonTest (for commonMain) or jvmTest (for "
            "jvmMain). Match the source set of the code under test.\n"
            "- The gate is: ./gradlew jvmTest must exit zero.\n"
            "Plain text, under 200 words.")

def land(patch, branch, title):
    patch_path = STATE_PATH + ".patch"
    open(patch_path, "w").write(patch)
    land_script = os.path.join(os.path.dirname(__file__) or ".", "land.py")
    p = subprocess.run(["python3", land_script, REPO_PATH, branch, patch_path,
                        "--commit", f"flywheel: {title}"],
                       capture_output=True, text=True, timeout=900)
    try: os.unlink(patch_path)
    except OSError: pass
    out = p.stdout.strip()
    try:
        j = json.loads(out)
        return bool(j.get("ok")), str(j.get("detail", out)), j
    except Exception:
        return False, out or p.stderr[:500], {}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--cycle-interval", type=int, default=POLL_INTERVAL)
    args = ap.parse_args()

    pq, live, landed, outcomes, harvested = load_state()
    main_branch = _main()
    print(f"agent-flywheel: REPO={REPO_PATH} BRANCH={main_branch} "
          f"MAX_LIVE={MAX_LIVE} STATE={STATE_PATH}", flush=True)

    cycle = 0
    while True:
        cycle += 1

        try:
            adopted = adopt_active_sessions(live, Jules.sessions(), harvested)
            if adopted:
                print(f"  cycle {cycle}: adopted {len(adopted)} live Jules sessions",
                      flush=True)
        except Exception as e:
            print(f"  session reconciliation failed: {e}", flush=True)

        if len(pq) + len(live) < MAX_LIVE * 2:
            snap = snapshot()
            print(f"  cycle {cycle}: snapshot {len(snap)}B; calling RESEARCHER…",
                  flush=True)
            props = unseen_proposals(proposal_list(brain_json(RESEARCHER,
                f"{snap}\n\nRecent outcomes:\n{list(outcomes)[-10:]}", [])),
                pq, live, outcomes)
            print(f"  cycle {cycle}: RESEARCHER returned "
                  f"{type(props).__name__} len={len(props) if isinstance(props,(list,str)) else '?'}",
                  flush=True)
            if isinstance(props, list) and props:
                keep = brain_json(TRIAGER, json.dumps(props),
                                   {"keep": list(range(len(props)))})
                print(f"  cycle {cycle}: TRIAGER returned "
                      f"{type(keep).__name__}: {str(keep)[:200]}", flush=True)
                indices = (keep or {}).get("keep", list(range(len(props))))
                added = 0
                known_titles = {w.title for w in pq}
                known_titles.update(
                    str(s.get("work", {}).get("title", "")) for s in live.values()
                )
                for i in indices:
                    if 0 <= i < len(props):
                        p = props[i]
                        try:
                            w = WorkItem(tier=str(p.get("tier","task")),
                                         score=float(p.get("score",0.5)),
                                         title=str(p.get("title","untitled")),
                                         spec=str(p.get("spec","")),
                                         parent=p.get("parent"))
                        except Exception: continue
                        if w.title in known_titles:
                            continue
                        pq.push(w); known_titles.add(w.title); added += 1
                if added:
                    print(f"  cycle {cycle}: research queued +{added} "
                          f"(depth={len(pq)})", flush=True)

        dispatch_available(pq, live, cycle)

        # Harvest COMPLETED sessions FIRST — they block new dispatch slots.
        # Polling IN_PROGRESS sessions is cheap; harvesting a completed one
        # runs the gate (70s), so do the highest-leverage work first.
        live_sorted = sorted(live.items(),
            key=lambda kv: 0 if kv[1].get("state") in ("COMPLETED","FINISHED") else 1)
        for name, sess in live_sorted:
            try: meta = Jules.get(name)
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    # Session deleted on Jules side — purge from live, mark harvested
                    print(f"  ⊘ 404 gone, purging: {name}", flush=True)
                    harvested.add(name)
                    del live[name]
                    save_state(pq, live, landed, outcomes, harvested)
                else:
                    print(f"  poll error {name}: {e}", flush=True)
                continue
            except Exception as e:
                print(f"  poll error {name}: {e}", flush=True); continue
            sess["state"] = meta.get("state", sess["state"])
            if sess["state"] != "AWAITING_USER_FEEDBACK":
                sess["answer_count"] = 0
            if sess["state"] == "AWAITING_PLAN_APPROVAL":
                try:
                    Jules.approve(name)
                    print(f"  approved plan: {name}", flush=True)
                except Exception as e:
                    print(f"  approve failed {name}: {e}", flush=True)
            try: acts = Jules.activities(name, after_ts=sess["last_poll"])
            except Exception as e:
                print(f"  activities error {name}: {e}", flush=True)
                acts = []
            if acts:
                timestamps = [a.get("createTime") for a in acts if a.get("createTime")]
                if timestamps:
                    sess["last_poll"] = max(timestamps)
                for a in acts:
                    if "planGenerated" in a:
                        try: Jules.approve(name)
                        except Exception: pass
                    for art in a.get("artifacts", []):
                        cs = art.get("changeSet")
                        if cs and change_set_patch(cs):
                            sess["patches"].append({"changeSet": cs})
                q = latest_question(acts)
                if confirmation_loop(sess["state"], sess.get("answer_count", 0),
                                     q, sess.get("last_question")):
                    reason = "Jules remained in a confirmation loop after two targeted answers"
                    loop_patch = client_patch(name)
                    if not loop_patch and sess["patches"]:
                        loop_patch = change_set_patch(sess["patches"][-1]["changeSet"])
                    outcomes.append({"title": sess["work"]["title"], "ok": False,
                                     "why": reason,
                                     "fingerprint": sess["work"].get("fingerprint")})
                    pq.push(retry_work(sess, reason, loop_patch))
                    harvested.add(name)
                    Jules.delete(name)
                    del live[name]
                    print(f"  ↻ confirmation loop, requeued: {sess['work']['title']}",
                          flush=True)
                    continue
                if q is not None and should_answer_question(
                    sess["state"], q, sess.get("last_question")
                ):
                    ans = brain_chat(ANSWERER,
                        f"Task title: {sess['work']['title']}\n"
                        f"Task spec: {sess['work']['spec']}\n\n"
                        f"Inquiry from the coding agent:\n{q}")
                    if not ans.startswith("__BRAIN_ERROR__"):
                        try:
                            Jules.send(name, ans)
                            sess["last_question"] = q
                            sess["answer_count"] = sess.get("answer_count", 0) + 1
                            print(f"  answered: {name} q='{q[:60]}'",
                                  flush=True)
                        except Exception as e:
                            print(f"  send failed {name}: {e}", flush=True)

            state = sess["state"]
            if state in ("COMPLETED", "FINISHED"):
                api_patch = ""
                if sess["patches"]:
                    api_patch = change_set_patch(sess["patches"][-1]["changeSet"])
                patch_text = client_patch(name) or api_patch
                if patch_text:
                    fp = sess["work"]["fingerprint"]
                    branch = f"flywheel/{fp}"
                    ok, msg, settlement = land(
                        patch_text, branch, sess["work"]["title"]
                    )
                    if ok:
                        landed += 1
                        informed, accounting_error = inform_merged_session(
                            name, settlement
                        )
                        outcomes.append({
                            "title": sess["work"]["title"],
                            "session": name,
                            "ok": True,
                            "fingerprint": fp,
                            "drainDate": settlement.get("drainDate"),
                            "parentSha": settlement.get("parentSha"),
                            "mergeSha": settlement.get("mergeSha"),
                            "commitSha": settlement.get("commitSha"),
                            "julesInformed": informed,
                            "accountingError": accounting_error,
                        })
                        print(f"  ✓ LANDED: {sess['work']['title']}", flush=True)
                        mark_drained(sess["work"]["title"], name)
                        if informed:
                            print(f"  ↳ merge receipt: {name}", flush=True)
                        else:
                            print(f"  ! merge receipt failed {name}: "
                                  f"{accounting_error}", flush=True)
                        harvested.add(name)
                        Jules.delete(name)
                        del live[name]
                    else:
                        outcomes.append({"title": sess["work"]["title"],
                                         "ok": False, "why": msg[:200],
                                         "fingerprint": fp})
                        pq.push(retry_work(sess, msg, patch_text))
                        harvested.add(name)
                        Jules.delete(name)
                        del live[name]
                        print(f"  ↻ gate red, requeued: {sess['work']['title']}: "
                              f"{msg[:200]}", flush=True)
                else:
                    reason = "Jules reached a terminal state without a client or API patch"
                    outcomes.append({"title": sess["work"]["title"], "ok": False,
                                     "why": reason,
                                     "fingerprint": sess["work"].get("fingerprint")})
                    pq.push(retry_work(sess, reason))
                    harvested.add(name)
                    Jules.delete(name)
                    del live[name]
                    print(f"  ↻ no patch, requeued: {sess['work']['title']}", flush=True)
            elif state in ("FAILED", "PAUSED"):
                    reason = session_interruption_reason(state)
                    outcomes.append({"title": sess["work"]["title"], "ok": False,
                                     "why": reason,
                                     "fingerprint": sess["work"].get("fingerprint")})
                    pq.push(retry_work(sess, reason))
                    harvested.add(name)
                    Jules.delete(name)
                    del live[name]
                    print(f"  ↻ failed, requeued: {sess['work']['title']}", flush=True)

            # Save state after each session so a slow cycle never loses progress.
            save_state(pq, live, landed, outcomes, harvested)

        dispatch_available(pq, live, cycle)

        save_state(pq, live, landed, outcomes, harvested)

        if args.once:
            print(f"agent-flywheel: one cycle. queue={len(pq)} live={len(live)} "
                  f"landed={landed} harvested={len(harvested)}", flush=True)
            return 0
        time.sleep(args.cycle_interval)

if __name__ == "__main__":
    sys.exit(main() or 0)
