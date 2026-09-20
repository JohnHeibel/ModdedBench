# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""EBF primitive regressions in journalled fixtures; never operate on the user's machines."""
from __future__ import annotations
import json, math, sys, time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'harness' / 'mcp'))
from kernel import Kernel, BridgeError

def main():
    evidence={'ok':False,'checks':[],'receipts':[]}
    out=ROOT/'gtnh/.runtime/evidence/primitive-regressions.json'
    def check(name,condition,detail=None):
        if not condition: raise AssertionError(f'{name}: {detail}')
        evidence['checks'].append(name);print(name,flush=True)
    with Kernel(url='ws://127.0.0.1:47223/ws',timeout=60) as c, Kernel(url='ws://127.0.0.1:47224/ws') as s:
        active=None
        config=c.call('time.status')['state']
        evidence['previousClock']=config
        def call(method,**params):
            try: result=c.call(method,**params)
            except BridgeError as error:
                evidence['receipts'].append({'method':method,'error':error.reply});raise
            evidence['receipts'].append({'method':method,'result':result});return result
        try:
            call('act.stop');call('gui.close')
            call('time.configure',healthDrop=False,healthBelow=-1,airBelow=-1,foodBelow=-1,burning=False,actionFailed=False,pauseOnDisconnect=False)
            call('time.pause')
            s.call('dev.interaction_fixture.create');active='dev.interaction_fixture'
            s.call(active+'.stack',slot=0,id='minecraft:baked_potato',count=4)
            call('time.resume');time.sleep(.5)
            call('act.select_hotbar',slot=0)
            try:call('act.eat',ticks=10)
            except BridgeError as error:
                check('native food duration rejects insufficient budget immediately','native_use_duration_exceeds_budget' in str(error),error.reply)
                check('rejected food releases controls',not call('obs.player')['controlActive'])
            else:raise AssertionError('insufficient eating budget was accepted')
            s.call(active+'.stack',slot=0,id='minecraft:bread',count=4);time.sleep(.3)
            before=call('obs.player')
            with ThreadPoolExecutor(1) as pool:
                eating=pool.submit(call,'act.eat')
                samples=[]
                while not eating.done():
                    samples.append(s.call(active+'.status'));time.sleep(.1)
                evidence['foodServerSamples']=samples
                receipt=eating.result()
            after=call('obs.player');server=s.call(active+'.status')
            evidence['foodServerAfter']=server
            check('food consumed in selected slot zero on client and server',after['held']['count']==before['held']['count']-1 and server['food']>before['food'] and 'Count:3b' in server['heldNbt'],receipt)
            check('native consumption completion acknowledged',receipt['serverAcknowledged'])
            check('eat releases controls',not after['controlActive'])
            check('eating does not activate the chest under the crosshair',call('obs.gui').get('class') is None)
            call('time.pause');s.call(active+'.restore');active=None
            fixture=s.call('dev.work_process_fixture.create');active='dev.work_process_fixture'
            base=fixture['origin']
            def coords(dx,y,dz):return dict(x=base[0]+dx,y=y,z=base[2]+dz)
            def block(dx,y,dz,id,meta=0):s.call(active+'.set_block',x=dx,y=y,z=dz,id=id,meta=meta)
            # Keep both instant-break targets level with the player's eye ray.
            for dx in (10,11):
                block(dx,176,11,'minecraft:dirt');block(dx,177,11,'minecraft:tallgrass',1)
            s.call(active+'.position',name='build')
            call('time.resume');time.sleep(.5)
            call('act.select_hotbar',slot=8)
            estimate=call('baritone.tools',**coords(10,177,11))
            check('tallgrass estimate is one tick',estimate['estimatedTicks']==1,estimate)
            p=call('obs.player');target=coords(10,177,11)
            dx=target['x']+.5-p['pos'][0];dz=target['z']+.5-p['pos'][2]
            dy=177.5-(p['pos'][1]+1.62)
            call('act.look',yaw=math.degrees(math.atan2(dz,dx))-90,pitch=-math.degrees(math.atan2(dy,math.hypot(dx,dz))))
            receipt=call('act.input',keys=['attack'],ticks=100)
            time.sleep(.2)
            check('held attack stops on original block change',receipt['outcome']=='attack_target_changed',receipt)
            check('original grass removed',s.call('obs.block',**coords(10,177,11))['id']=='minecraft:air')
            check('grass behind target preserved',s.call('obs.block',**coords(11,177,11))['id']=='minecraft:tallgrass')
            check('attack releases controls',not call('obs.player')['controlActive'])
            block(10,177,11,'minecraft:tallgrass',1);time.sleep(.2)
            receipt=call('act.input',keys=['attack'],ticks=20,allowRetarget=True)
            check('explicit retargeting remains available',receipt['outcome']=='duration_elapsed' and s.call('obs.block',**coords(11,177,11))['id']=='minecraft:air',receipt)
            # Builder must clear grass itself, without the old raw-input workaround.
            block(10,177,11,'minecraft:tallgrass',1);time.sleep(.2)
            pos=list(coords(10,177,11).values())
            receipt=call('baritone.build',selection={'min':pos,'max':pos,'shape':'fill','block':{'id':'minecraft:cobblestone','meta':0}},mode='builder',allowBreak=True,allowPlace=True,replaceExisting=True,timeoutTicks=400)
            check('builder clears tallgrass and places block',receipt['state']=='succeeded' and s.call('obs.block',**coords(10,177,11))['id']=='minecraft:cobblestone',receipt)
            evidence['ok']=True
        finally:
            try:
                call('act.stop');call('gui.close');call('time.pause')
                if active:s.call(active+'.restore')
                # The normal human handoff uses free-running time with no disconnect pause.
                call('time.configure',**config['conditions'])
                if not config.get('paused',False):call('time.resume')
            finally:
                out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(evidence,indent=2))
                print(out,flush=True)
    return 0 if evidence['ok'] else 1

if __name__=='__main__':raise SystemExit(main())
