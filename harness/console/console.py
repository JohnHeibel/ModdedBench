# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Operator console for contained runs: one local web page over docker compose, the launcher and the bridge.

    python harness/console/console.py          then open http://127.0.0.1:47300

It listens on the host's loopback only and every action needs the page's token, so neither another site
in your browser nor the agent (which has no route to the host) can press its buttons. It holds no logic of
its own: each button is a command you could have typed, and the job log shows which.
"""
from __future__ import annotations
import argparse, hashlib, json, os, re, secrets, shutil, subprocess, sys, threading, time, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(REPO / "harness" / "launcher"), str(REPO / "harness" / "mcp")]
import runtime
from kernel import Kernel

DOCKER = shutil.which("docker") or "C:/Program Files/Docker/Docker/resources/bin/docker.exe"  # a shell opened before the install lacks the PATH entry
COMPOSE = [DOCKER, "compose", "-f", str(REPO / "docker" / "compose.yaml"), "--env-file", str(REPO / "docker" / ".env")]
PY = [sys.executable, "-u"]
LAUNCHER, DEPLOY = str(REPO / "harness" / "launcher" / "runtime.py"), str(REPO / "harness" / "launcher" / "deploy.py")
BRIEF = REPO / ".runtime" / "brief"
OVERLAY = REPO / ".runtime" / "outbox" / "overlay"  # written by the loop (harness/runner/feed.py)
TOKEN = secrets.token_urlsafe(24)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
# One line of JSON about the loop, produced inside the agent container.
AGENT_PROBE = ("import json,subprocess,pathlib;s=pathlib.Path('.state');r=lambda n:(s/n).read_text(errors='replace') if (s/n).exists() else '';"
               "print(json.dumps({'running':subprocess.run(['pgrep','-f','[c]odex_loop.py'],capture_output=True).returncode==0,"
               "'stopRequested':(s/'STOP').exists(),'thread':r('codex-loop.json'),'prompt':pathlib.Path('/brief/PROMPT.md').exists(),"
               "'commits':subprocess.run(['git','rev-list','--count','modbench-base..HEAD'],capture_output=True,text=True).stdout.strip(),"
               "'log':r('codex-loop.log')[-6000:]}))")


def fill_prompt(prompt, quest, chapter):
    """PROMPT.md with its four placeholders filled for a contained run."""
    if not quest or not chapter or any(len(x) > 200 or '"' in x or "\n" in x for x in (quest, chapter)): raise ValueError("target quest and chapter are required, without quotes")
    for key, value in (("TARGET_QUEST", quest), ("TARGET_CHAPTER", chapter), ("WORLD", "the contained GTNH server (one world)"), ("REPO", "/work/modbench")):
        prompt, n = re.subn(rf'^({key}\s*=\s*)"<[^\n]*>"', lambda m: f'{m.group(1)}"{value}"', prompt, count=1, flags=re.M)
        if n != 1: raise ValueError(f"PROMPT.md has no {key} placeholder")
    return prompt


class Shortener:
    """Long goals and long remarks, shortened for the stream by a cheap model, off the request path and cached on disk.

    It runs here on the host (OPENROUTER_API_KEY in the console's environment), never where the agent is, and the
    agent never sees the result. Without a key, or until an answer arrives, or if the call fails, the page shows the original, clamped.
    """
    ASK = {"goal": (70, "This is an AI agent's current goal in a modded Minecraft factory run. Rewrite it as one short imperative goal of at most 9 words. Keep the specific item or machine. Add nothing that is not there. Answer with the goal only."),
           "say": (220, "This is a remark by an AI agent playing a modded Minecraft factory game, shown to stream viewers. Rewrite it in the first person in at most two short plain sentences. Keep concrete items, numbers and the reason for what it does. Drop tables, lists and formatting. Add nothing that is not there. Answer with the rewrite only.")}

    def __init__(self, path):
        self.path, self.lock, self.pending, self.slots = path, threading.Lock(), set(), threading.Semaphore(2)
        self.model = os.environ.get("MB_OVERLAY_MODEL", "mistralai/mistral-small-2603")
        try: self.cache = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError): self.cache = {}

    def get(self, kind, text):
        text = str(text or ""); key = kind + hashlib.sha1(text.encode()).hexdigest()[:16]
        if len(text) <= self.ASK[kind][0] or not os.environ.get("OPENROUTER_API_KEY"): return None
        with self.lock:
            if key in self.cache or key in self.pending: return self.cache.get(key)
            self.pending.add(key)
        threading.Thread(target=self.fetch, args=(kind, text, key), daemon=True).start()

    def fetch(self, kind, text, key):
        try:
            with self.slots:
                body = {"model": self.model, "max_tokens": 120, "temperature": 0.2, "messages": [{"role": "system", "content": self.ASK[kind][1]}, {"role": "user", "content": text[:4000]}]}
                request = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", json.dumps(body).encode(),
                                                 {"Authorization": "Bearer " + os.environ["OPENROUTER_API_KEY"], "Content-Type": "application/json"})
                with urllib.request.urlopen(request, timeout=30) as reply: short = json.load(reply)["choices"][0]["message"]["content"].strip().strip('"')
            with self.lock:
                if short and len(short) < len(text): self.cache[key] = short; self.path.parent.mkdir(parents=True, exist_ok=True); self.path.write_text(json.dumps(self.cache), encoding="utf-8")
        except Exception: pass  # the original stays on screen; the key stays pending, so this text is not asked about again in this session


def sh(cmd, stdin=None, timeout=30):
    return subprocess.run(cmd, cwd=REPO, input=stdin, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout, creationflags=NO_WINDOW)


class Console:
    def __init__(self):
        self.lock = threading.Lock(); self.kernel = None; self.supervisor = None
        self.job = {"name": "", "running": False, "ok": True, "log": ""}; self.login = (0.0, None); self.book = (0.0, []); self.shorten = Shortener(runtime.RUNTIME / "overlay-short.json")

    # The bridge, read only: one long-lived session for the state panel.
    def call(self, method, **params):
        with self.lock:
            if self.kernel is None or not self.kernel.connected:
                if not runtime.bridge_is_live(runtime.CLIENT_PORT): raise ConnectionError("the client bridge is down")
                self.kernel = Kernel(url=f"ws://127.0.0.1:{runtime.CLIENT_PORT}/ws", timeout=5)
            kernel = self.kernel
        return kernel.call(method, timeout=5, **params)

    def game(self):
        out = {"bridge": runtime.bridge_is_live(runtime.CLIENT_PORT)}
        for key, method in (("world", "obs.world"), ("player", "obs.player"), ("time", "time.status"), ("quests", "quest.status")):
            if not out["bridge"]: break
            try: out[key] = self.call(method)
            except Exception as e: out[key] = {"error": str(e)}
        return out

    def state(self):
        ps = sh([*COMPOSE, "ps", "-a", "--format", "json"])
        services = {}
        for line in ps.stdout.splitlines():
            try: row = json.loads(line); services[row["Service"]] = {"state": row.get("State"), "status": row.get("Status")}
            except (ValueError, KeyError): pass
        agent = {}
        if services.get("agent", {}).get("state") == "running":
            probe = sh([*COMPOSE, "exec", "-T", "agent", "python3", "-c", AGENT_PROBE])
            try: agent = json.loads(probe.stdout)
            except ValueError: agent = {"error": (probe.stderr or probe.stdout)[-300:]}
            if time.monotonic() - self.login[0] > 60:
                self.login = (time.monotonic(), sh([*COMPOSE, "exec", "-T", "agent", "codex", "login", "status"]).returncode == 0)
            agent["loggedIn"] = self.login[1]
        deploys = []
        for folder in sorted((runtime.RUNTIME / "deploys").glob("*"), reverse=True)[:8]:
            req, res = runtime.load_json(folder / "request.json"), runtime.load_json(folder / "result.json")
            deploys.append({"time": folder.name, "components": req.get("components"), "reason": req.get("reason"), "ok": res.get("ok"), "error": res.get("error")})
        return {"docker": ps.returncode == 0, "dockerError": ps.stderr[-300:], "services": services, "agent": agent, "game": self.game(), "deploys": deploys,
                "supervisor": self.supervisor is not None and self.supervisor.poll() is None, "job": self.job}

    def overlay(self):
        """Everything the OBS pages show, read only: the loop's feed and totals, plus the clock and the quest book from the bridge."""
        try: live = json.loads((OVERLAY / "live.json").read_text(encoding="utf-8"))
        except (OSError, ValueError): live = {"goal": {}, "status": {}, "stats": {}}
        try: feed = [json.loads(x) for x in (OVERLAY / "feed.jsonl").read_text(encoding="utf-8").splitlines()[-400:]]  # runs of one tool collapse on the page, so many lines make few rows
        except (OSError, ValueError): feed = []
        if time.monotonic() - self.book[0] > 20:  # 46 lines with their layouts: read rarely
            try:
                lines = self.call("quest.lines", query="", offset=0, limit=100)["lines"]
                target = re.search(r'^TARGET_CHAPTER\s*=\s*"([^"<]+)"', (BRIEF / "PROMPT.md").read_text(encoding="utf-8"), re.M)
                last = next((i for i, l in enumerate(lines) if target and (l["name"] in target.group(1) or target.group(1) in l["name"])), 3)
                self.book = (time.monotonic(), [{"name": l["name"], "completed": l["completed"], "total": l["quests"]} for l in lines[:last + 1]])
            except Exception: self.book = (time.monotonic(), self.book[1])
        try: clock = self.call("time.status")["state"]
        except Exception: clock = None
        status = dict(live.get("status") or {})
        if clock is None: status = {"state": "game_down", "text": "", "since": status.get("since")}
        elif clock.get("paused") and status.get("state") in ("thinking", "acting", "waiting"):  # between turns the world is always paused; that is not news
            status = {"state": "held" if clock.get("held") else "paused", "text": "" if clock.get("held") else str(clock.get("reason") or "").replace("_", " "), "since": status.get("since")}
        goal = dict(live.get("goal") or {}); goal["short"] = self.shorten.get("goal", goal.get("subgoal"))
        for entry in [e for e in feed if e.get("kind") == "say"][-12:]: entry["short"] = self.shorten.get("say", entry.get("text"))
        try: target = re.search(r'^TARGET_QUEST\s*=\s*"([^"<]+)"', (BRIEF / "PROMPT.md").read_text(encoding="utf-8"), re.M).group(1)
        except (OSError, AttributeError): target = ""
        return {"now": time.time(), "goal": goal, "status": status, "stats": live.get("stats"), "run": live.get("run"), "target": target, "feed": feed, "chapters": self.book[1]}

    # Actions. Anything slow runs as the single background job; its command lines and output are the job log.
    def run_job(self, name, steps):
        if self.job["running"]: raise RuntimeError(f"'{self.job['name']}' is still running")
        self.job = job = {"name": name, "running": True, "ok": True, "log": ""}
        def work():
            try:
                for step in steps:
                    cmd, stdin = step if isinstance(step, tuple) else (step, None)
                    job["log"] += "$ " + " ".join(cmd[len(COMPOSE):] if cmd[:len(COMPOSE)] == COMPOSE else cmd[1:] if cmd[:1] == PY[:1] else cmd) + "\n"
                    result = sh(cmd, stdin, timeout=900); job["log"] = (job["log"] + result.stdout + result.stderr)[-8000:]
                    if result.returncode: job["ok"] = False; break
            except Exception as e: job["ok"] = False; job["log"] += f"{type(e).__name__}: {e}\n"
            job["running"] = False
        threading.Thread(target=work, daemon=True).start()

    def act(self, name, a):
        agent = [*COMPOSE, "exec", "-T", "agent"]
        if name == "server.start": self.run_job(name, [[*COMPOSE, "up", "-d", "server"]])
        elif name == "server.stop": self.run_job(name, [[*COMPOSE, "stop", "server"]])
        elif name in ("time.pause", "time.resume"):  # the operator hold: a file in the server directory, which outranks every bridge session
            def hold(*cmd):
                done = sh([*COMPOSE, "exec", "-T", "server", *cmd, "/data/modbench-hold"])
                if done.returncode: raise RuntimeError((done.stderr or done.stdout).strip()[-300:])
            if name == "time.pause": hold("touch")
            else:  # the server resumes when the hold goes away, so a pause someone else left is taken over first
                try: left = self.call("time.status")["state"]; left = left["paused"] and not left.get("held")
                except Exception: left = False
                if left: hold("touch"); time.sleep(0.5)
                hold("rm", "-f")
        elif name == "client.launch": self.run_job(name, [[*PY, LAUNCHER, "launch-client", "--installed-as-is"]])
        elif name == "client.stop": self.run_job(name, [[*PY, LAUNCHER, "stop-client"]])
        elif name == "client.install":  # the host's own build of this checkout, client side only
            self.run_job(name, [[*PY, LAUNCHER, "build"], [*PY, LAUNCHER, "install-core", "--side", "client"], [*PY, LAUNCHER, "install-client"], [*PY, LAUNCHER, "install-baritone"]])
        elif name == "supervisor.start":
            if self.supervisor is None or self.supervisor.poll() is not None:
                log = runtime.RUNTIME / "logs" / "deploy-supervisor.log"; log.parent.mkdir(parents=True, exist_ok=True)
                self.supervisor = subprocess.Popen([*PY, DEPLOY, "serve"], cwd=REPO, stdout=log.open("ab"), stderr=subprocess.STDOUT, creationflags=NO_WINDOW)
        elif name == "supervisor.stop":
            if self.supervisor: self.supervisor.terminate()
        elif name == "agent.start":
            self.act("supervisor.start", {})  # the brief promises the agent a deploy supervisor; a request nobody answers looks like a hang
            extra = ["--", "-m", a["model"]] if re.fullmatch(r"[\w.\-]{1,64}", a.get("model") or "") else []
            if a.get("effort") in ("minimal", "low", "medium", "high", "xhigh"): extra = [*(extra or ["--"]), "-c", f'model_reasoning_effort="{a["effort"]}"']
            turns = str(max(1, min(int(a.get("maxTurns") or 200), 10000)))
            loop = "rm -f .state/STOP; p=PROMPT.md; [ -f /brief/PROMPT.md ] && p=/brief/PROMPT.md; exec python3 harness/runner/codex_loop.py --prompt $p --max-turns \"$0\" \"$@\" >/dev/null 2>&1"
            self.run_job(name, [[*COMPOSE, "up", "-d", "gateway", "agent"], [*COMPOSE, "exec", "-d", "agent", "sh", "-c", loop, turns, *extra]])
        elif name == "agent.stop": self.run_job(name, [[*agent, "sh", "-c", "mkdir -p .state && touch .state/STOP"]])
        elif name == "agent.kill": self.run_job(name, [[*agent, "sh", "-c", "pkill -f '[c]odex_loop.py'; pkill -x codex; pkill -f '[h]arness/mcp/server.py'; true"]])
        elif name == "agent.down": self.run_job(name, [[*COMPOSE, "stop", "agent", "gateway"]])
        elif name == "run.init":
            prompt = fill_prompt((REPO / "PROMPT.md").read_text(encoding="utf-8"), str(a.get("targetQuest", "")).strip(), str(a.get("targetChapter", "")).strip())
            up = "agent" in sh([*COMPOSE, "ps", "--services", "--status", "running"]).stdout.split()
            steps = [[*agent, "sh", "-c", "pkill -f '[c]odex_loop.py'; pkill -x codex; true"]] if up else []
            if a.get("freshWorld"): steps += [[*COMPOSE, "rm", "-sf", "server"], [DOCKER, "volume", "rm", "-f", "moddedbench_server-data"]]
            if a.get("freshAgent"): steps += [[*COMPOSE, "rm", "-sf", "agent"], [DOCKER, "volume", "rm", "-f", "moddedbench_agent-work"]]
            # The brief lives on the host and is mounted read-only at /brief: the agent can read its mission and rules but not rewrite them.
            shutil.rmtree(OVERLAY, ignore_errors=True)  # a new run starts a new feed and new totals
            BRIEF.mkdir(parents=True, exist_ok=True); (BRIEF / "PROMPT.md").write_text(prompt, encoding="utf-8", newline="")
            steps += [[*COMPOSE, "up", "-d"], [*agent, "sh", "-c", "mkdir -p .state && rm -f .state/STOP .state/codex-loop.json .state/run-prompt.md"]]
            self.run_job(name, steps)
        else: raise ValueError(f"unknown action {name}")
        return {"accepted": name}


class Handler(BaseHTTPRequestHandler):
    console: Console
    def log_message(self, *args): pass
    def reply(self, code, body, kind="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body, default=str).encode()
        self.send_response(code); self.send_header("Content-Type", kind); self.send_header("Content-Length", str(len(data))); self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(data)
    def trusted(self):  # a DNS-rebinding page has the wrong Host; a cross-site form has no token
        return self.headers.get("Host", "").split(":")[0] in ("127.0.0.1", "localhost")
    def do_GET(self):
        if not self.trusted(): return self.reply(403, {"error": "forbidden"})
        if self.path == "/": return self.reply(200, (Path(__file__).with_name("console.html").read_text(encoding="utf-8").replace("__TOKEN__", TOKEN)).encode(), "text/html; charset=utf-8")
        # The overlay is for OBS browser sources, which cannot send the token: it is read only and says nothing the stream does not show.
        if self.path.split("?")[0] == "/overlay": return self.reply(200, Path(__file__).with_name("overlay.html").read_bytes(), "text/html; charset=utf-8")
        if self.path == "/overlay/mock": return self.reply(200, Path(__file__).with_name("overlay_mock.html").read_bytes(), "text/html; charset=utf-8")
        if self.path == "/overlay/data":
            try: return self.reply(200, self.console.overlay())
            except Exception as e: return self.reply(500, {"error": f"{type(e).__name__}: {e}"})
        if self.path == "/api/state" and self.headers.get("X-Console-Token") == TOKEN:
            try: return self.reply(200, self.console.state())
            except Exception as e: return self.reply(500, {"error": f"{type(e).__name__}: {e}"})
        self.reply(404, {"error": "not found"})
    def do_POST(self):
        if not self.trusted() or self.headers.get("X-Console-Token") != TOKEN or self.path != "/api/action": return self.reply(403, {"error": "forbidden"})
        try:
            body = json.loads(self.rfile.read(min(int(self.headers.get("Content-Length") or 0), 1 << 16)) or b"{}")
            self.reply(200, {"ok": True, "result": self.console.act(str(body.get("name")), body.get("args") or {})})
        except Exception as e: self.reply(200, {"ok": False, "error": f"{type(e).__name__}: {e}"})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--port", type=int, default=47300); args = parser.parse_args(argv)
    Handler.console = Console()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"[ModdedBench] operator console at http://127.0.0.1:{args.port}", flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally:
        if Handler.console.supervisor: Handler.console.supervisor.terminate()


if __name__ == "__main__":
    main()
