# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Composable, process-local interrupt watches for the GTNH bridge.

``add`` accepts either ``queries`` plus a declarative ``condition``, or a
Python ``file`` whose ``evaluate(context)`` returns bool or
``{"match": bool, "payload": {...}}``.  Conditions address observation data
with ``alias.path`` (``items[0].count`` is also accepted).  They are deliberately
small and read-only: all/any/not, eq/ne/lt/lte/gt/gte, exists,
changed/increased/decreased, and collection any/all are supported.
"""
from __future__ import annotations
import asyncio, copy, json, math, os, sqlite3, threading, time, uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

_OPS = {"eq": lambda a,b:a==b, "ne":lambda a,b:a!=b, "lt":lambda a,b:a<b,
        "lte":lambda a,b:a<=b, "gt":lambda a,b:a>b, "gte":lambda a,b:a>=b}
_singleton = None
_singleton_kernel = None
_singleton_lock = threading.RLock()

def _wakes_runner(event):
    return event["kind"] in ("triggered","reaction_error","fault","stalled") or (
        event["kind"]=="disarmed" and event.get("data",{}).get("reason")=="context_changed")

def _path(value, path):
    if not path: return value
    for bit in path.replace("[", ".").replace("]", "").split("."):
        if not bit: continue
        value = value[int(bit)] if isinstance(value, list) else value[bit]
    return value

def _condition(c, values, previous):
    if not isinstance(c, dict) or len(c) != 1: raise ValueError("condition must be a one-key object")
    op, arg = next(iter(c.items()))
    # ``any``/``all`` accept either a Boolean list or {path, where}; the latter
    # evaluates where against each item as ``$.field``.
    if op in ("all", "any") and isinstance(arg, dict) and "path" in arg:
        seq = _path(values, arg["path"]); child = arg["where"]
        return (all if op == "all" else any)(_condition(child, {"$": x}, previous) for x in seq)
    if op in ("all", "any"): return (all if op == "all" else any)(_condition(x, values, previous) for x in arg)
    if op == "not": return not _condition(arg, values, previous)
    if op == "exists":
        try: _path(values, arg); return True
        except (KeyError, IndexError, TypeError, ValueError): return False
    if op in ("changed", "increased", "decreased"):
        try: a = _path(values,arg)
        except (KeyError, IndexError, TypeError, ValueError): raise ValueError("missing current value for "+op+": "+arg)
        try: b = _path(previous,arg)
        except (KeyError, IndexError, TypeError, ValueError): return False # first sample has no previous value
        return a != b if op == "changed" else (a > b if op == "increased" else a < b)
    if op in ("any_of", "all_of"):
        seq = _path(values, arg["path"]); child = arg["where"]
        return (any if op == "any_of" else all)(_condition(child, {"$": x}, previous) for x in seq)
    if op in _OPS:
        if not isinstance(arg, list) or len(arg) != 2: raise ValueError(op + " needs [path, value]")
        return _OPS[op](_path(values,arg[0]), arg[1])
    raise ValueError("unknown condition " + op)

def _validate_condition(c, depth=0):
    if depth > 32: raise ValueError("condition nesting exceeds 32")
    if not isinstance(c,dict) or len(c)!=1: raise ValueError("condition must be a one-key object")
    op,arg=next(iter(c.items()))
    if op == "not": return _validate_condition(arg,depth+1)
    if op in ("all","any"):
        if isinstance(arg,list):
            for x in arg: _validate_condition(x,depth+1)
            return
        if isinstance(arg,dict) and isinstance(arg.get("path"),str) and "where" in arg: return _validate_condition(arg["where"],depth+1)
        raise ValueError(op+" needs condition list or {path, where}")
    if op in ("exists","changed","increased","decreased"):
        if not isinstance(arg,str): raise ValueError(op+" needs path")
        return
    if op in _OPS:
        if not isinstance(arg,list) or len(arg)!=2 or not isinstance(arg[0],str): raise ValueError(op+" needs [path, value]")
        return
    if op in ("any_of","all_of"):
        if not isinstance(arg,dict) or not isinstance(arg.get("path"),str) or "where" not in arg: raise ValueError(op+" needs {path, where}")
        return _validate_condition(arg["where"],depth+1)
    raise ValueError("unknown condition "+op)

class ReadContext:
    def __init__(self, sup, values, state, previous, methods): self._sup, self.values, self.state, self.previous, self._methods = sup, values, state, previous, methods
    def read(self, method, **params):
        meta = self._methods.get(method, {})
        if meta.get("effect") != "read": raise ValueError("predicate may only call advertised read methods: " + method)
        return self._sup.kernel.call(method, **params)
    def prompt(self, text, **observations):
        """Yield an open decision to the model when this predicate matches."""
        _validate_prompt(text)
        return {"match": True, "prompt": text, "payload": observations}


def _validate_prompt(text):
    if not isinstance(text, str) or not text.strip() or len(text) > 8192:
        raise ValueError("prompt must contain 1..8192 characters")

@dataclass
class Watch:
    name: str; spec: dict; generation: int = 0; armed: bool = True; state: dict = field(default_factory=dict)
    previous: dict = field(default_factory=dict); context: dict | None = None; operation_id: int | None = None
    hits: int = 0; last_fire: float = 0; running: bool = False; callable: Any = None; dispatches: set = field(default_factory=set); reacting: bool = False

class InterruptSupervisor:
    """Autonomous polling; the supplied shared Kernel remains owned by the caller."""
    def __init__(self, kernel, path=None, retained=1000, poll_s=.10, read_timeout_s=5):
        if poll_s <= 0 or not isinstance(retained,int) or retained < 1: raise ValueError("poll_s and retained must be positive")
        if read_timeout_s <= 0: raise ValueError("read_timeout_s must be positive")
        self.kernel, self.retained = kernel, retained; self.lock = threading.RLock(); self.changed = threading.Condition(self.lock); self.watches = {}; self.poll_s=poll_s; self.read_timeout_s=read_timeout_s; self._methods_cache=None
        default_root = Path(__file__).resolve().parents[2] / "gtnh" / ".state" / "interrupts"
        root = Path(path or os.environ.get("MODBENCH_INTERRUPTS_DIR", default_root)); root.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(str(root / "events.sqlite3"), check_same_thread=False); self._closed=False; self._poll_lock=threading.Lock(); self._stop=threading.Event()
        self.db.execute("create table if not exists events (id integer primary key autoincrement, ts real, kind text, name text, data text)"); self.db.commit()
        self._scheduler=threading.Thread(target=self._run,name="interrupt-supervisor",daemon=True); self._scheduler.start()
    def _run(self):
        while not self._stop.wait(self.poll_s):
            try: self.poll()
            except Exception as e:
                # A transport failure must not kill future polling.
                try: self._event("fault",error="scheduler: "+str(e))
                except Exception: pass
    def _event(self, kind, name="", **data):
        with self.lock:
            if self._closed: return
            self.db.execute("insert into events(ts,kind,name,data) values(?,?,?,?)", (time.time(),kind,name,json.dumps(data,default=str))); self.db.execute("delete from events where id <= (select max(id)-? from events)",(self.retained,)); self.db.commit(); self.changed.notify_all()
    def _methods(self):
        if self._methods_cache is not None: return self._methods_cache
        raw = self.kernel.call("sys.methods", timeout=self.read_timeout_s)
        raw = raw.get("methods", raw) if isinstance(raw,dict) else raw
        if isinstance(raw,dict): self._methods_cache=raw; return raw
        if isinstance(raw,list): self._methods_cache={x["name"]:x for x in raw if isinstance(x,dict) and isinstance(x.get("name"),str)}; return self._methods_cache
        raise ValueError("sys.methods must be a method map or array")
    def _load(self, w):
        # Compile source directly so explicit reload never accepts a same-second
        # stale .pyc file from an editable user workspace.
        p = Path(w.spec["file"]); scope={"__file__":str(p),"__name__":"interrupt_"+w.name}
        exec(compile(p.read_text(encoding="utf-8"),str(p),"exec"),scope)
        fn = scope.get("evaluate")
        if not callable(fn): raise ValueError("custom file must define evaluate(context)")
        return fn
    def _validate(self, spec):
        if not isinstance(spec,dict): raise ValueError("spec must be object")
        if ("file" in spec) == ("condition" in spec): raise ValueError("spec requires exactly one of file or condition")
        if "file" in spec and (not isinstance(spec["file"],str) or not spec["file"]): raise ValueError("file must be a nonempty path")
        if "condition" in spec: _validate_condition(spec["condition"])
        if not isinstance(spec.get("queries",{}),dict) or len(spec.get("queries",{})) > 16: raise ValueError("queries must be an object of at most 16 entries")
        for a,q in spec.get("queries",{}).items():
            if not isinstance(a,str) or len(a)>128 or not isinstance(q,dict) or not isinstance(q.get("method"),str) or len(q["method"])>256 or not isinstance(q.get("params",{}),dict): raise ValueError("query %s requires method and object params" % a)
            if len(json.dumps(q)) > 65536: raise ValueError("query %s exceeds 64KiB" % a)
        effects=spec.get("effects",["notify"])
        if not isinstance(effects,list) or not effects or len(effects)>3 or len(set(effects))!=len(effects) or any(x not in ("notify","cancel","pause") for x in effects): raise ValueError("effects must be unique known effects")
        for key,lo,hi,integer in (("consecutive",1,1000,True),("debounce",1,1000,True),("cooldown",0,86400,False),("timeout_s",.001,86400,False),("read_timeout_s",.001,30,False)):
            if key in spec and (isinstance(spec[key],bool) or not isinstance(spec[key],(int,float)) or not math.isfinite(spec[key]) or spec[key]<lo or spec[key]>hi or (integer and int(spec[key])!=spec[key])): raise ValueError("invalid "+key)
        for key in ("oneShot","edge","latch","operationScope"):
            if key in spec and not isinstance(spec[key],bool): raise ValueError(key+" must be bool")
        if "reason" in spec and (not isinstance(spec["reason"],str) or not spec["reason"].strip() or len(spec["reason"])>240): raise ValueError("reason must contain 1..240 characters")
        if "prompt" in spec: _validate_prompt(spec["prompt"])
        try:
            if len(json.dumps(spec)) > 262144: raise ValueError("spec exceeds 256KiB")
        except (TypeError, ValueError) as e: raise ValueError("spec must be bounded JSON: "+str(e))
    def add(self, name, spec, replace=False):
        if not isinstance(name,str) or not name or len(name)>128: raise ValueError("name must be a nonempty string up to 128 chars")
        self._validate(spec)
        with self.lock:
            if name in self.watches and not replace: raise ValueError("watch already exists")
            if name not in self.watches and len(self.watches) >= 64: raise ValueError("at most 64 watches")
            old=self.watches.get(name)
            # Compile before touching a working replacement.
            w=Watch(name,copy.deepcopy(spec),generation=(old.generation+2 if old else 1));
            if "file" in spec: w.callable=self._load(w)
            if old: old.armed=False; old.generation+=1 # invalidate an in-flight custom callable
            self.watches[name]=w
        self._event("armed",name,spec={k:v for k,v in spec.items() if k != "file"}); return self.status(name)
    def remove(self,name):
        with self.lock:
            w=self.watches.pop(name,None)
            if not w: return False
            w.armed=False; w.generation+=1
        self._event("disarmed",name,reason="removed"); return True
    def reload(self,name):
        with self.lock:
            w=self.watches[name]; old=w.callable
            try: new=self._load(w)
            except Exception as e: self._event("reload_failed",name,error=str(e)); raise
            w.generation+=1; w.running=False; w.reacting=False; w.dispatches.clear(); w.state={}; w.callable=new # old worker has an old state reference and cannot commit
        self._event("reloaded",name); return self.status(name)
    def _status(self,w): return {"name":w.name,"armed":w.armed,"running":w.running,"state":dict(w.state),"context":w.context,"operationId":w.operation_id}
    def list(self):
        with self.lock: return [self._status(w) for w in self.watches.values()]
    def status(self,name=None):
        with self.lock:
            if name is None: return {"watches":self.list()}
            w=self.watches.get(name)
            return self._status(w) if w else {"name":name,"armed":False,"running":False,"state":{},"context":None,"operationId":None}
    def events(self,after=0,limit=100,wait_s=0):
        with self.changed:
            if self._closed: raise RuntimeError("interrupt supervisor closed")
            if wait_s: self.changed.wait_for(lambda: self._closed or self.db.execute("select 1 from events where id>?",(after,)).fetchone(), wait_s)
            if self._closed: raise RuntimeError("interrupt supervisor closed")
            first=self.db.execute("select min(id) from events").fetchone()[0]; gap=bool(first and after and after < first-1)
            rows=self.db.execute("select id,ts,kind,name,data from events where id>? order by id limit ?",(max(after, first-1 if gap else after),limit)).fetchall()
        return {"gap":gap,"cursor":rows[-1][0] if rows else after,"events":[{"id":i,"ts":t,"kind":k,"name":n,"data":json.loads(d)} for i,t,k,n,d in rows]}
    def _fire(self,w,ctx,payload):
        if w.reacting: return
        try:
            payload = dict(payload)
            if "prompt" in w.spec and "modelPrompt" not in payload:
                payload["modelPrompt"] = w.spec["prompt"]
            if "modelPrompt" in payload: _validate_prompt(payload["modelPrompt"])
            if len(json.dumps(payload)) > 65536: raise ValueError("trigger payload exceeds 64KiB")
        except (TypeError, ValueError) as e:
            self._fault(w,"payload: "+str(e)); return
        effects=w.spec.get("effects",["notify"]); eid=str(uuid.uuid4()); args={"eventId":eid,"reason":w.spec.get("reason",w.name),"effects":effects,"expectedContext":ctx,"latch":w.spec.get("latch", any(x in effects for x in ("cancel","pause"))),"payload":payload}
        if w.operation_id is not None: args["expectedOperationId"]=w.operation_id
        token=str(uuid.uuid4()); w.dispatches.add(token); generation=w.generation; w.reacting=True
        if w.spec.get("oneShot",True): w.armed=False; self._event("disarmed",w.name,reason="oneShot")
        # Removal can cancel a queued dispatch until this worker claims it.  Once
        # interrupt.fire is sent, its urgent bridge side effect is irreversible.
        threading.Thread(target=self._send_fire,args=(w,generation,token,eid,args,payload),daemon=True).start()
    def _send_fire(self,w,generation,token,eid,args,payload):
        with self.lock:
            if self._closed or self.watches.get(w.name) is not w or w.generation != generation or token not in w.dispatches: return
            w.dispatches.discard(token) # claim immediately before the irreversible bridge call
        try:
            receipt=self.kernel.call("interrupt.fire",timeout=self.read_timeout_s,**args)
            self._event("triggered",w.name,eventId=eid,payload=payload,receipt=receipt)
        except Exception as e: self._event("reaction_error",w.name,eventId=eid,payload=payload,error=str(e)) # never retry ambiguous fire
        finally:
            with self.lock:
                if w.generation==generation: w.reacting=False
    def _finish_custom(self,w,gen,ctx,values,methods,fn,state,previous):
        try:
            out=fn(ReadContext(self,values,state,previous,methods))
            if isinstance(out,bool): match,payload=out,{}
            elif isinstance(out,dict) and isinstance(out.get("match"),bool) and isinstance(out.get("payload",{}),dict): match,payload=out["match"],out.get("payload",{})
            else: raise ValueError("custom evaluate must return bool or {match: bool, payload: object}")
            if isinstance(out,dict) and "prompt" in out:
                _validate_prompt(out["prompt"])
                payload = {**payload, "modelPrompt": out["prompt"]}
        except Exception as e:
            with self.lock:
                if w.generation != gen: return
                w.running=False; w.state.pop("_started",None)
            self._fault(w,e); return
        with self.lock:
            if self._closed or not w.armed or w.generation != gen or w.context != ctx: return
            w.running=False; w.state.pop("_started",None)
            self._match(w,match,payload,ctx)
    def _match(self,w,match,payload,ctx):
        w.hits=w.hits+1 if match else 0; needed=int(w.spec.get("consecutive",w.spec.get("debounce",1)))
        qualified=bool(match and w.hits >= needed); was=bool(w.state.get("_edge_qualified")); w.state["_edge_qualified"]=qualified
        if qualified and (not w.spec.get("edge") or not was) and time.monotonic()-w.last_fire >= float(w.spec.get("cooldown",0)):
            w.last_fire=time.monotonic(); self._fire(w,ctx,payload)
    def _fault(self,w,error):
        """Observation/predicate faults are terminal: false must never hide them."""
        with self.lock:
            if self.watches.get(w.name) is not w or not w.armed: return
            w.armed=False; w.generation+=1
        self._event("fault",w.name,error=str(error)); self._event("disarmed",w.name,reason="fault")
    def poll(self):
        if self._closed or not self._poll_lock.acquire(False): return
        try: self._poll()
        finally: self._poll_lock.release()
    def _poll(self):
        with self.lock:
            if not any(w.armed for w in self.watches.values()): return
        try: methods=self._methods()
        except Exception as e:
            with self.lock: active=list(self.watches.values())
            for w in active: self._fault(w,"sys.methods: "+str(e))
            return
        with self.lock: watches=list(self.watches.values())
        for w in watches:
            if not w.armed: continue
            if w.reacting: continue # at most one pending reaction per watch, including slow coordinated pause
            if w.running:
                if time.monotonic()-w.state.get("_started",time.monotonic()) >= float(w.spec.get("timeout_s",5)):
                    with self.lock:
                        if w.running: w.armed=False; w.generation+=1; self._event("stalled",w.name,timeout_s=w.spec.get("timeout_s",5)); self._event("disarmed",w.name,reason="worker_timeout")
                continue
            try:
                for q in w.spec.get("queries",{}).values():
                    meta=methods.get(q["method"], {})
                    if meta.get("effect") != "read" or not meta.get("watchable",False):
                        raise ValueError("query method is not an advertised watchable read: " + q["method"])
                deadline=float(w.spec.get("read_timeout_s",self.read_timeout_s))
                r=self.kernel.call("obs.batch",timeout=deadline,queries=w.spec.get("queries",{})); values=r.get("values",{}); errors=r.get("errors",{}); ctx=r.get("context",{});
                if errors: raise RuntimeError("observation errors: "+json.dumps(errors))
                missing=set(w.spec.get("queries",{}))-set(values)
                if missing: raise RuntimeError("missing observations: "+", ".join(sorted(missing)))
                op=self.kernel.call("interrupt.status",timeout=deadline); opctx=op.get("context"); oid=op.get("operationId")
                with self.lock:
                    if self.watches.get(w.name) is not w or not w.armed: continue # removed/replaced while a read was in flight
                    keys=("worldId","dimension","bridgeId","worldEpoch")
                    if any(k not in ctx for k in keys): raise RuntimeError("observation context missing required fields")
                    if not isinstance(opctx,dict) or any(opctx.get(k)!=ctx.get(k) for k in keys): raise RuntimeError("interrupt.status context differs from observations")
                    if w.context is not None and any(ctx.get(k)!=w.context.get(k) for k in keys):
                        w.armed=False; self._event("disarmed",w.name,reason="context_changed"); continue
                    if w.context is None: w.context=ctx; w.operation_id=oid if w.spec.get("operationScope") else None
                    if "file" in w.spec:
                        w.running=True; w.state["_started"]=time.monotonic()
                        threading.Thread(target=self._finish_custom,args=(w,w.generation,ctx,values,methods,w.callable,w.state,w.previous),daemon=True).start()
                    else:
                        self._match(w,_condition(w.spec["condition"],values,w.previous),{},ctx)
                    w.previous=values
            except Exception as e: self._fault(w,e)
    def close(self):
        with self.lock:
            if self._closed: return
            self._closed=True; self._stop.set()
            for w in self.watches.values(): w.armed=False; w.generation+=1
            self.changed.notify_all()
        if threading.current_thread() is not self._scheduler: self._scheduler.join(timeout=max(.2,self.poll_s*3))
        with self.lock: self.db.close()

def get_supervisor(kernel, path=None):
    global _singleton,_singleton_kernel
    with _singleton_lock:
        if _singleton is None or _singleton_kernel is not kernel:
            if _singleton: _singleton.close()
            _singleton,_singleton_kernel=InterruptSupervisor(kernel,path),kernel
        return _singleton
def close_supervisor():
    global _singleton,_singleton_kernel
    with _singleton_lock:
        if _singleton: _singleton.close()
        _singleton=_singleton_kernel=None

async def race_interrupt(supervisor, model_awaitable, cursor=0, cancel=None, poll_s=.05):
    """Return model result or an interruption; provider cancellation is caller supplied."""
    task=asyncio.ensure_future(model_awaitable)
    async def stop():
        if not task.done(): task.cancel()
        if cancel: cancel()
        # A provider wrapper may suppress cancellation. Its obsolete result must
        # not prevent the runner from receiving the interrupt.
        def consume(completed):
            try: completed.result()
            except (asyncio.CancelledError, Exception): pass
        task.add_done_callback(consume)
        await asyncio.wait({task},timeout=.25)
    try:
        while True:
            e=supervisor.events(cursor,wait_s=0)
            if e["events"] or e.get("gap"):
                cursor=e["cursor"]
                if e.get("gap") or any(_wakes_runner(x) for x in e["events"]):
                    await stop()
                    return {"interrupted":True,"gap":e.get("gap",False),"cursor":cursor,"events":e["events"]}
            if task.done():
                # An event arriving in the same scheduling slice wins over a model result.
                e=supervisor.events(cursor,wait_s=0); cursor=e["cursor"]
                if e.get("gap") or any(_wakes_runner(x) for x in e["events"]):
                    await stop(); return {"interrupted":True,"gap":e.get("gap",False),"cursor":cursor,"events":e["events"]}
                return {"interrupted":False,"cursor":cursor,"result":await task}
            await asyncio.sleep(poll_s)
    except asyncio.CancelledError:
        await stop()
        raise
