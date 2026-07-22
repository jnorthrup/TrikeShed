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
    def activities(name, after_ts=None):
        url = f"{JULES_BASE}/{name}/activities?pageSize=50"
        activities = http("GET", url).get("activities", [])
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

def unseen_proposals(proposals, pq, live):
    known_titles = {w.title for w in pq}
    known_titles.update(
        str(s.get("work", {}).get("title", "")) for s in live.values()
    )
    unseen = []
    for proposal in proposals:
        title = str(proposal.get("title", "")) if isinstance(proposal, dict) else ""
        if not title or title in known_titles:
            continue
        unseen.append(proposal)
        known_titles.add(title)
    return unseen

def load_state():
    pq = WorkPQ(); live = {}; landed = 0; outcomes = deque(maxlen=50)
    if not os.path.exists(STATE_PATH): return pq, live, landed, outcomes
    try:
        with open(STATE_PATH) as f:
            data = json.load(f)
    except Exception: return pq, live, landed, outcomes
    for w in data.get("queue", []):
        pq.push(WorkItem(tier=w.get("tier","task"), score=float(w.get("score",0.5)),
                         title=w.get("title",""), spec=w.get("spec",""),
                         parent=w.get("parent"), attempt=int(w.get("attempt", 0)),
                         feedback=str(w.get("feedback", ""))))
    live.update(data.get("live", {}))
    landed = int(data.get("landed", 0))
    for o in data.get("outcomes", []): outcomes.append(o)
    return pq, live, landed, outcomes

def save_state(pq, live, landed, outcomes):
    tmp = STATE_PATH + ".tmp"
    payload = {"queue": [{"tier": w.tier, "score": w.score, "title": w.title,
                          "spec": w.spec, "parent": w.parent,
                          "attempt": w.attempt, "feedback": w.feedback} for w in pq],
               "live": live, "landed": landed, "outcomes": list(outcomes),
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
        spec = (f"{w.spec}{repair}\n\nRULES: TDD strictly — commit failing "
                f"tests first, then implement to green. Do NOT open "
                f"a PR; produce a final patch/changeset only. Reply "
                f"with KEEP_GOING if you have no question.")
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
            "patches": [], "last_question": None,
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
            "owner. Be decisive, concrete, unblockingly specific. Prefer TDD, "
            "minimal diffs, existing conventions. Plain text, under 200 words. "
            "If the inquiry is just a status update, reply KEEP_GOING and a "
            "1-line restatement of the task spec.")

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
        return bool(j.get("ok")), str(j.get("detail", out))
    except Exception:
        return False, out or p.stderr[:500]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--cycle-interval", type=int, default=POLL_INTERVAL)
    args = ap.parse_args()

    pq, live, landed, outcomes = load_state()
    main_branch = _main()
    print(f"agent-flywheel: REPO={REPO_PATH} BRANCH={main_branch} "
          f"MAX_LIVE={MAX_LIVE} STATE={STATE_PATH}", flush=True)

    cycle = 0
    while True:
        cycle += 1

        if len(pq) + len(live) < MAX_LIVE * 2:
            snap = snapshot()
            print(f"  cycle {cycle}: snapshot {len(snap)}B; calling RESEARCHER…",
                  flush=True)
            props = unseen_proposals(proposal_list(brain_json(RESEARCHER,
                f"{snap}\n\nRecent outcomes:\n{list(outcomes)[-10:]}", [])),
                pq, live)
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

        for name, sess in list(live.items()):
            try: meta = Jules.get(name)
            except Exception as e:
                print(f"  poll error {name}: {e}", flush=True); continue
            sess["state"] = meta.get("state", sess["state"])
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
                    q = _extract_question(a)
                    if q and q != sess.get("last_question"):
                        ans = brain_chat(ANSWERER,
                            f"Task: {sess['work']['spec']}\nInquiry: {q}")
                        if not ans.startswith("__BRAIN_ERROR__"):
                            try:
                                Jules.send(name, ans)
                                sess["last_question"] = q
                                print(f"  answered: {name} q='{q[:60]}'",
                                      flush=True)
                            except Exception as e:
                                print(f"  send failed {name}: {e}", flush=True)
                    for art in a.get("artifacts", []):
                        cs = art.get("changeSet")
                        if cs and cs.get("gitPatch"):
                            sess["patches"].append({"changeSet": cs})

            state = sess["state"]
            if state in ("COMPLETED", "FINISHED") and sess["patches"]:
                cs = sess["patches"][-1]["changeSet"]
                patch_text = cs.get("gitPatch","")
                if not patch_text:
                    Jules.delete(name); del live[name]; continue
                fp = sess["work"]["fingerprint"]
                branch = f"flywheel/{fp}"
                ok, msg = land(patch_text, branch, sess["work"]["title"])
                if ok:
                    landed += 1
                    outcomes.append({"title": sess["work"]["title"], "ok": True})
                    print(f"  ✓ LANDED: {sess['work']['title']}", flush=True)
                    Jules.delete(name); del live[name]
                else:
                    outcomes.append({"title": sess["work"]["title"],
                                     "ok": False, "why": msg[:200],
                                     "fingerprint": sess["work"].get("fingerprint")})
                    pq.push(retry_work(sess, msg, patch_text))
                    Jules.delete(name)
                    del live[name]
                    print(f"  ↻ gate red, requeued: {sess['work']['title']}: "
                          f"{msg[:200]}", flush=True)
            elif state in ("COMPLETED", "FINISHED"):
                reason = "Jules reached a terminal state without emitting a gitPatch"
                outcomes.append({"title": sess["work"]["title"], "ok": False,
                                 "why": reason,
                                 "fingerprint": sess["work"].get("fingerprint")})
                pq.push(retry_work(sess, reason))
                Jules.delete(name)
                del live[name]
                print(f"  ↻ no patch, requeued: {sess['work']['title']}", flush=True)
            elif state == "FAILED":
                reason = "Jules session failed before producing a landable patch"
                outcomes.append({"title": sess["work"]["title"], "ok": False,
                                 "why": reason,
                                 "fingerprint": sess["work"].get("fingerprint")})
                pq.push(retry_work(sess, reason))
                Jules.delete(name)
                del live[name]
                print(f"  ↻ failed, requeued: {sess['work']['title']}", flush=True)

        dispatch_available(pq, live, cycle)

        save_state(pq, live, landed, outcomes)

        if args.once:
            print(f"agent-flywheel: one cycle. queue={len(pq)} live={len(live)} "
                  f"landed={landed}", flush=True)
            return 0
        time.sleep(args.cycle_interval)

if __name__ == "__main__":
    sys.exit(main() or 0)
