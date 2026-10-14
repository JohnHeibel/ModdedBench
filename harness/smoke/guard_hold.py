# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""A guard's pause holds the work in flight instead of ending it, in the game.

Each scene runs in the combat course's box. Part-way through, the fixture hurts the player so that a guard pauses the
world; the tool's call must come back with state suspended, the guard as pausedBy and a suspendedJobId, the server must
show the work unfinished, and mb_work_resume(job_id, resume=True) must then finish it:

  eat     bread in hand at hunger 8: held mid-bite, then eaten.
  walk    a goal 30 blocks off: held on the way, then reached.
  fight   a zombie, with the healthBelow guard tripped by the fixture: held, then won.
  ended   an eat held by a guard, then a different action: that ends the eat, and resuming it answers cancelled.
  script  the walk inside mb_run: the script stops at the guard, whatever it catches, and names the held job.

And the same pauses answered by a reflex (mb_reflex): a script armed beforehand, run at the pause with no one asked.

  reflex_walk    a reflex that only looks: the one walk call comes back arrived.
  reflex_fight   a reflex that eats at low health: its actions end the held fight, which is sent again and won.
  reflex_threat  a walk past a zombie, a reflex that fights what the threat guard names: one call, zombie dead, arrived.
  reflex_idle    no call in flight when the guard pauses: the reflex still runs, and time goes on.
  reflex_fail    a reflex that raises: the pause and the held walk stand exactly as with no reflex.
  reflex_script  the walk inside mb_run with the looking reflex armed: the script runs through the guard.

    MB_CLIENT_INSTANCE=<instance>/.minecraft bash harness/smoke/mbtest.sh harness/smoke/guard_hold.py [--case NAME ...]

Needs the throwaway mbtest stack with the dev fixtures (dev.combat.*), any pack.
Evidence: .runtime/evidence/guard-hold.json.
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import combat_course as cc  # noqa: E402
import movement_course as mc  # noqa: E402
from movement_course import BridgeError, core, work  # noqa: E402
from mbtools_gtnh import reflexes, scripts  # noqa: E402

OUT = mc.ROOT / ".runtime" / "evidence" / "guard-hold.json"
BREAD = 2
FAR = [int(cc.START[0]) + 30, cc.FLOOR, int(cc.START[2])]


class Hold(cc.Combat):
    def ready(self, **reset):
        self.quiet(); self.revive()
        for r in reflexes.mb_reflex()["armed"]: reflexes.mb_reflex(r["name"], remove=True)   # a scene starts with none armed
        reflexes.deliver()
        self.s.call(cc.FIX + ".arena", **cc.ARENA); self.s.call(cc.FIX + ".clear")
        self.s.call(cc.FIX + ".reset", empty=True, armor=cc.ARMOR, at=list(cc.START), yaw=-90, effects=cc.SIGHT,
                    stacks=cc.STACKS + [{"slot": BREAD, "id": "minecraft:bread", "count": 8}], **reset)
        time.sleep(.5)

    def guard(self, **on):
        self.c.call("time.configure", **{**cc.OFF, **on})

    def player(self):
        return self.s.call(cc.FIX + ".status", radius=160)["player"]

    def hurt_in(self, seconds: float, amount: float = 2):
        """The stimulus, from the server's own session, while the tool's call is blocked on the client's."""
        t = threading.Timer(seconds, lambda: self.s.call(cc.FIX + ".hurt", amount=amount)); t.daemon = True; t.start()

    def call(self, fn, *args, **kwargs):
        """The tool's answer, or its error with the receipt the error carried."""
        try: return fn(*args, **kwargs)
        except BridgeError as e:
            err = (e.reply or {}).get("error") or {}
            return {**(err.get("receipt") if isinstance(err.get("receipt"), dict) else {}), "error": f"{e.code}: {e.msg}"[:300]}

    def held(self, got: dict, guard: str) -> list[str]:
        clock = self.c.call("time.status")["state"]
        wrong = []
        if got.get("state") != "suspended": wrong.append(f"state {got.get('state')!r}, not suspended ({got.get('error')})")
        if got.get("pausedBy") != guard: wrong.append(f"pausedBy {got.get('pausedBy')!r}, not {guard}")
        if not got.get("suspendedJobId"): wrong.append("no suspendedJobId")
        if not clock.get("paused") or clock.get("reason") != guard: wrong.append(f"clock {clock.get('reason')!r} paused={clock.get('paused')}")
        return wrong

    def bread(self):
        return next((i["count"] for i in self.player()["inventory"] if i["slot"] == BREAD), 0)

    # -- scenes: each returns (problems, evidence)
    def eat(self):
        self.ready(food=8); self.c.call("act.select_hotbar", slot=BREAD); self.guard(healthDrop=True)
        self.hurt_in(.6)
        got = self.call(core.mb_act, "eat")
        wrong, mid = self.held(got, "health_dropped"), (self.player()["food"], self.bread())
        if mid != (8, 8): wrong.append(f"food, bread {mid} at the pause: the eat was not unfinished")
        done = self.call(mc.with_resume, True, work.mb_work_resume, got.get("suspendedJobId") or "none")
        time.sleep(.5); end = (self.player()["food"], self.bread())
        if done.get("state") != "completed" or end[0] <= 8 or end[1] != 7: wrong.append(f"after resume: state {done.get('state')!r} {done.get('error')}, food, bread {end}")
        return wrong, {"held": {k: got.get(k) for k in ("state", "pausedBy", "elapsedTicks", "nativeUseTicks")}, "atPause": mid, "resumed": done.get("state"), "end": end}

    def walk(self):
        self.ready(); self.guard(healthDrop=True)
        self.hurt_in(1.5)
        got = self.call(work.mb_process, "goal", goal={"type": "block", "pos": FAR})
        wrong, mid = self.held(got, "health_dropped"), self.player()["pos"]
        if not 2 < mid[0] - cc.START[0] < 28: wrong.append(f"at the pause the player stood at {mid}: not on the way")
        done = self.call(mc.with_resume, True, work.mb_work_resume, got.get("suspendedJobId") or "none")
        time.sleep(.5); end = self.player()["pos"]
        if done.get("state") != "succeeded" or abs(end[0] - (FAR[0] + .5)) > 1: wrong.append(f"after resume: state {done.get('state')!r} {done.get('error')}, at {end}")
        return wrong, {"held": {k: got.get(k) for k in ("state", "pausedBy", "ticks")}, "atPause": mid, "resumed": done.get("state"), "end": end}

    def fight(self):
        self.ready(); self.s.call(cc.FIX + ".clear")
        mob = self.s.call(cc.FIX + ".summon", type="Zombie", x=cc.START[0] + 10, y=cc.FLOOR, z=cc.START[2])["summoned"][0]
        self.seen({mob["entityId"]}); self.guard(healthBelow=17)
        self.hurt_in(1.0, amount=5)
        got = self.call(work.mb_fight, entity_id=mob["entityId"], weapon_slot=cc.SWORD, timeout_s=90)
        wrong = self.held(got, "health_threshold")
        alive = [e for e in self.s.call(cc.FIX + ".status", radius=160)["entities"] if e["uuid"] == mob["uuid"]]
        if not alive: wrong.append("the zombie was already dead at the pause: nothing was left to resume")
        done = self.call(mc.with_resume, True, work.mb_work_resume, got.get("suspendedJobId") or "none")
        time.sleep(2)
        left = [e for e in self.s.call(cc.FIX + ".status", radius=160)["entities"] if e["uuid"] == mob["uuid"]]
        if done.get("state") != "succeeded" or left: wrong.append(f"after resume: state {done.get('state')!r} {done.get('reason')} {done.get('error')}, zombie alive: {bool(left)}")
        return wrong, {"held": {k: got.get(k) for k in ("state", "pausedBy", "ticks", "attacks", "phase")}, "resumed": {k: done.get(k) for k in ("state", "reason", "ticks", "attacks")}}

    def ended(self):
        self.ready(food=8); self.c.call("act.select_hotbar", slot=BREAD); self.guard(healthDrop=True)
        self.hurt_in(.6)
        got = self.call(core.mb_act, "eat")
        wrong = self.held(got, "health_dropped")
        other = self.call(mc.with_resume, True, core.mb_act, "select_hotbar", {"slot": cc.SWORD})   # any new action ends the held one
        again = self.call(work.mb_work_resume, got.get("suspendedJobId") or "none")
        time.sleep(.5); end = (self.player()["food"], self.bread())
        if "cancelled" not in str(again.get("error")) or end != (8, 8): wrong.append(f"the held eat after a new action: {again.get('state')!r} {again.get('error')}, food, bread {end}")
        return wrong, {"newAction": other.get("state"), "resumeSays": again.get("error"), "end": end}

    def script(self):
        self.ready(); self.guard(healthDrop=True)
        self.hurt_in(1.5)
        code = f"def main():\n try:\n  mb_process('goal', goal={{'type': 'block', 'pos': {FAR}}})\n except Exception:\n  pass\n mb_process('goal', goal={{'type': 'block', 'pos': {FAR}}}, resume=True)\n return 'walked on'"
        out = scripts.mb_run(code=code)
        note, clock, wrong = out.get("interrupted") or {}, self.c.call("time.status")["state"], []
        if "result" in out or note.get("pausedBy") != "health_dropped" or not note.get("suspendedJobId"): wrong.append(f"the script was not stopped at the guard: {json.dumps(out)[:400]}")
        if not clock.get("paused"): wrong.append("the world is running: the script resumed through the guard")
        done = self.call(mc.with_resume, True, work.mb_work_resume, note.get("suspendedJobId") or "none")
        if done.get("state") != "succeeded": wrong.append(f"after resume: state {done.get('state')!r} {done.get('error')}")
        return wrong, {"interrupted": {k: note.get(k) for k in ("tool", "pausedBy", "suspendedJobId")}, "line": out.get("line"), "resumed": done.get("state")}


    # -- the same pauses, answered by a reflex
    def arm(self, when: str, do: str, **more):
        reflexes.mb_reflex("r", when=when, do=do, **more)

    def fired(self, wrong: list[str], **want) -> dict:
        """The one firing the model would be handed, and the clock after it."""
        got, clock = reflexes.deliver(), self.c.call("time.status")["state"]
        first = got[0] if got else {}
        if len(got) != 1: wrong.append(f"{len(got)} firings handed over, not 1: {json.dumps(got)[:400]}")
        for key, value in want.items():
            if first.get(key) != value: wrong.append(f"the firing's {key} is {first.get(key)!r}, not {value!r} ({first.get('stopped')})")
        running = first.get("world") == "running"
        if bool(clock.get("paused")) == running: wrong.append(f"the firing says the world is {first.get('world')}; the clock says paused={clock.get('paused')} ({clock.get('reason')})")
        return first

    LOOK = "def main(event):\n return mb_obs('player')['health']"

    def reflex_walk(self):
        self.ready(); self.guard(healthDrop=True); self.arm("health_dropped", self.LOOK)
        self.hurt_in(1.5)
        got, wrong = self.call(work.mb_process, "goal", goal={"type": "block", "pos": FAR}), []
        time.sleep(.5); end = self.player()
        if got.get("state") != "succeeded" or abs(end["pos"][0] - (FAR[0] + .5)) > 1: wrong.append(f"the walk: state {got.get('state')!r} {got.get('error')}, at {end['pos']}")
        if end["health"] > 18: wrong.append("the player was never hurt: no guard was there to answer")
        fired = self.fired(wrong, when="health_dropped", work="carried_on", world="running")
        return wrong, {"walk": got.get("state"), "fired": fired}

    def reflex_fight(self):
        self.ready(food=8); self.s.call(cc.FIX + ".clear")
        mob = self.s.call(cc.FIX + ".summon", type="Zombie", x=cc.START[0] + 10, y=cc.FLOOR, z=cc.START[2])["summoned"][0]
        self.seen({mob["entityId"]}); self.guard(healthBelow=17)
        self.arm("health_threshold", f"def main(event):\n mb_act('select_hotbar', {{'slot': {BREAD}}})\n return mb_act('eat').get('state')")
        self.hurt_in(1.0, amount=5)
        got, wrong = self.call(work.mb_fight, entity_id=mob["entityId"], weapon_slot=cc.SWORD, timeout_s=90), []
        time.sleep(2); end = (self.player()["food"], self.bread())
        left = [e for e in self.s.call(cc.FIX + ".status", radius=160)["entities"] if e["uuid"] == mob["uuid"]]
        if got.get("state") != "succeeded" or left: wrong.append(f"the fight: state {got.get('state')!r} {got.get('reason')} {got.get('error')}, zombie alive: {bool(left)}")
        if end[0] <= 8 or end[1] != 7: wrong.append(f"food, bread {end}: the reflex did not eat")
        fired = self.fired(wrong, when="health_threshold", work="restarted", world="running", result="completed")
        return wrong, {"fight": {k: got.get(k) for k in ("state", "reason", "ticks", "attacks")}, "end": end, "fired": fired}

    def reflex_threat(self):
        self.ready(); self.s.call(cc.FIX + ".clear")
        self.arm("threat", f"def main(event):\n return mb_fight(entity_id=event['threats'][0]['entityId'], weapon_slot={cc.SWORD}, timeout_s=60).get('reason')", max_ticks=600)
        mob = self.s.call(cc.FIX + ".summon", type="Zombie", x=cc.START[0] + 20, y=cc.FLOOR, z=cc.START[2])["summoned"][0]
        self.seen({mob["entityId"]}); self.guard(threatWithin=12)
        got, wrong = self.call(work.mb_process, "goal", goal={"type": "block", "pos": FAR}), []
        time.sleep(2); end = self.player()["pos"]
        left = [e for e in self.s.call(cc.FIX + ".status", radius=160)["entities"] if e["uuid"] == mob["uuid"]]
        if got.get("state") != "succeeded" or abs(end[0] - (FAR[0] + .5)) > 1: wrong.append(f"the walk: state {got.get('state')!r} {got.get('error')}, at {end}")
        if left: wrong.append("the zombie is alive: the reflex did not win its fight")
        fired = self.fired(wrong, when="threat", world="running", result="target_dead")
        if fired.get("work") not in ("restarted", "sent", None): wrong.append(f"the walk was {fired.get('work')}")  # the zombie may notice before the walk is sent, or before any call
        return wrong, {"walk": got.get("state"), "fired": fired}

    def reflex_idle(self):
        self.ready(); self.guard(healthDrop=True); self.arm("health_dropped", "def main(event):\n return [event['work'], mb_obs('player')['health']]")
        started = time.monotonic(); self.s.call(cc.FIX + ".hurt", amount=2)
        while time.monotonic() - started < 5 and (self.c.call("time.status")["state"].get("paused") or not reflexes._box()["fired"]): time.sleep(.05)
        wall, wrong = round(time.monotonic() - started, 2), []
        fired = self.fired(wrong, when="health_dropped", world="running")
        if "work" in fired or (fired.get("result") or [1])[0] is not None: wrong.append(f"a call was said to be in flight: {fired.get('work')} {fired.get('result')}")
        return wrong, {"answeredInS": wall, "fired": fired}

    def reflex_fail(self):
        self.ready(); self.guard(healthDrop=True); self.arm("health_dropped", "def main(event):\n log('looked')\n raise KeyError('no plan for this')")
        self.hurt_in(1.5)
        got = self.call(work.mb_process, "goal", goal={"type": "block", "pos": FAR})
        wrong = self.held(got, "health_dropped")
        fired = self.fired(wrong, when="health_dropped", work="held", world="paused", line=3)
        if "KeyError" not in str(fired.get("stopped")): wrong.append(f"the firing does not say why it stopped: {fired.get('stopped')!r}")
        done = self.call(mc.with_resume, True, work.mb_work_resume, got.get("suspendedJobId") or "none")
        if done.get("state") != "succeeded": wrong.append(f"after resume: state {done.get('state')!r} {done.get('error')}")
        return wrong, {"held": {k: got.get(k) for k in ("state", "pausedBy")}, "fired": fired, "resumed": done.get("state")}

    def reflex_script(self):
        self.ready(); self.guard(healthDrop=True); self.arm("health_dropped", self.LOOK)
        self.hurt_in(1.5)
        out, wrong = scripts.mb_run(code=f"def main():\n return mb_process('goal', goal={{'type': 'block', 'pos': {FAR}}})['state']"), []
        time.sleep(.5); end = self.player()
        if out.get("result") != "succeeded" or "interrupted" in out or abs(end["pos"][0] - (FAR[0] + .5)) > 1: wrong.append(f"the script did not run through the guard: {json.dumps(out)[:400]}, at {end['pos']}")
        if end["health"] > 18: wrong.append("the player was never hurt: no guard was there to answer")
        fired = self.fired(wrong, when="health_dropped", work="carried_on", world="running")
        return wrong, {"script": out.get("result"), "fired": fired}


CASES = ("eat", "walk", "fight", "ended", "script", "reflex_walk", "reflex_fight", "reflex_threat", "reflex_idle", "reflex_fail", "reflex_script")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--case", action="append", choices=CASES)
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--server-host", default="127.0.0.1")
    args = ap.parse_args(); args.seed, args.trials, args.guards, args.avoidance, args.aim_height = 1, 1, False, False, None
    t = Hold(args); t.setup()
    rows = {}
    try:
        for name in args.case or CASES:
            try: wrong, seen = getattr(t, name)()
            except Exception as e: wrong, seen = [f"{type(e).__name__}: {e}"[:300]], {}
            rows[name] = {"ok": not wrong, "wrong": wrong, **seen}
            print(f"{name:13} {'ok  ' if not wrong else 'FAIL'} {json.dumps({k: v for k, v in rows[name].items() if k != 'ok'})}", flush=True)
    finally:
        for r in reflexes.mb_reflex()["armed"]: reflexes.mb_reflex(r["name"], remove=True)
        reflexes._stop(); t.teardown()
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(json.dumps({"ok": bool(rows) and all(r["ok"] for r in rows.values()), "cases": rows}, indent=1), encoding="utf-8")
    sys.exit(0 if rows and all(r["ok"] for r in rows.values()) else 1)


if __name__ == "__main__":
    main()
