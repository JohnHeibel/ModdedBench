# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Batched native survival interactions and autonomous interrupt acceptance."""
from __future__ import annotations
import asyncio, json, os, sys, tempfile, time, uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'harness' / 'mcp'))
from kernel import Kernel, BridgeError, bridge_url
import mbtool  # noqa: F401  (installs the mbtools_gtnh package)
from mbtools_gtnh.interrupts import InterruptSupervisor, race_interrupt
USER = os.environ.get("MB_USERNAME", "ModbenchDev")

def main():
    evidence={'ok':False,'checks':[],'receipts':[]}
    def check(name,ok,detail=None):
        if not ok: raise AssertionError(f'{name}: {detail}')
        evidence['checks'].append(name);print(name,flush=True)
    with Kernel() as c, Kernel(url=bridge_url('server')) as s, tempfile.TemporaryDirectory() as tmp, ThreadPoolExecutor(2) as pool:
        sup=InterruptSupervisor(c,path=tmp)
        created=False
        def call(method,**kw):
            try: out=c.call(method,**kw)
            except BridgeError as error: evidence['receipts'].append(error.reply);raise
            evidence['receipts'].append(out);return out
        def refuse(name,method,**kw):
            try: call(method,**kw)
            except BridgeError as e: evidence['receipts'].append(e.reply);check(name,True)
            else: raise AssertionError(name+' unexpectedly accepted')
        def at(target):
            call('act.stop');call('gui.close');call('time.pause');out=s.call('dev.interaction_fixture.position',target=target);call('time.resume');time.sleep(.3);return out
        def select(slot):return call('act.select_hotbar',slot=slot)
        def fired(name,seconds=5):
            end=time.monotonic()+seconds
            while time.monotonic()<end:
                events=sup.events()['events']
                for e in events:
                    if e['name']==name and e['kind']=='triggered':return e
                if any(e['name']==name and e['kind'] in ('fault','reaction_error') for e in events):raise AssertionError(events)
                time.sleep(.05)
            raise AssertionError('interrupt did not fire: '+name+' '+str(sup.events()))
        def settled_player():time.sleep(.25);return call('obs.player')
        try:
            call('time.configure',healthDrop=False,healthBelow=-1,airBelow=-1,actionFailed=False,pauseOnDisconnect=True)
            call('time.pause');s.call('dev.interaction_fixture.restore');time.sleep(.2)
            base=next(p for p in s.call('obs.players') if p['name']==USER);arena=s.call('dev.interaction_fixture.create');created=True
            call('time.resume');time.sleep(.5)
            methods={m['name']:m for m in call('sys.methods')}
            check('watchable discovery',methods['obs.player']['watchable'] and not methods['obs.entity']['watchable'])
            batch=call('obs.batch',queries={'p':{'method':'obs.player'},'e':{'method':'obs.entities'},'bad':{'method':'act.input'}})
            check('batched read isolation',set(batch['values'])=={'p','e'} and 'bad' in batch['errors'])
            chest=dict(zip(('x','y','z'),arena['chest']))
            select(8)
            observed=call('obs.block',**chest)
            r=call('act.use_block',**chest,face=4,expected={'id':observed['id'],'meta':observed['meta']})
            check('native chest activation',r['nativeReturn'] and call('obs.container')['windowId']>0,r)
            call('gui.close');select(3)
            r=call('act.use_block',**chest,face=4,sneak=True,expectedHeld=call('obs.player')['held'])
            check('sneak placement bypasses activation',r['nativeReturn'] and call('obs.gui').get('class') is None,r)
            check('placement observed',call('obs.block',x=chest['x']-1,y=chest['y'],z=chest['z'])['id']=='minecraft:cobblestone')
            refuse('stale held refused','act.use_item',expectedHeld={'id':'minecraft:dirt'})
            at('fluid');select(0);fluid=dict(zip(('x','y','z'),arena['fluid']))
            call('nav.goto',x=fluid['x'],y=177,z=fluid['z']-1,timeoutTicks=100)
            r=call('act.use_item',**fluid,fluid=True,face=1)
            check('source pickup native bucket',settled_player()['held']['id']=='minecraft:water_bucket',r)
            check('water collection verified on server',s.call('obs.block',**fluid)['id']=='minecraft:air')
            r=call('act.use_item',x=fluid['x'],y=175,z=fluid['z'],face=1)
            check('source placement native bucket',settled_player()['held']['id']=='minecraft:bucket',r)
            check('placed fluid observed',call('obs.block',**fluid)['id'] in ('minecraft:water','minecraft:flowing_water'))
            check('water placement verified on server',s.call('obs.block',**fluid)['id'] in ('minecraft:water','minecraft:flowing_water'))
            at('lava');call('nav.goto',x=fluid['x'],y=177,z=fluid['z']-1,timeoutTicks=100)
            r=call('act.use_item',**fluid,fluid=True,face=1)
            check('lava source pickup',settled_player()['held']['id']=='minecraft:lava_bucket',r)
            check('lava collection verified on server',s.call('obs.block',**fluid)['id']=='minecraft:air')
            r=call('act.use_item',x=fluid['x'],y=175,z=fluid['z'],face=1)
            check('lava source placement',settled_player()['held']['id']=='minecraft:bucket' and call('obs.block',**fluid)['id'] in ('minecraft:lava','minecraft:flowing_lava'),r)
            check('lava placement verified on server',s.call('obs.block',**fluid)['id'] in ('minecraft:lava','minecraft:flowing_lava'))
            select(1);food=call('obs.player')['food'];r=call('act.eat',ticks=100)
            check('native food consumed',settled_player()['food']>food,r)
            at('entity');select(0);cow=next(e for e in call('obs.entities',radius=8)['entities'] if e['type']=='Cow')
            refuse('stale entity handle refused','act.use_entity',entityId=cow['entityId'],expectedHandle='stale')
            r=call('act.use_entity',entityId=cow['entityId'],expectedHandle=cow['handle'])
            check('native entity use milks cow',settled_player()['held']['id']=='minecraft:milk_bucket',r)
            select(4);future=pool.submit(c.call,'act.use_item',ticks=100);time.sleep(.2);call('act.stop')
            try:future.result(4)
            except BridgeError:pass
            check('held use cancellation releases input',not call('obs.player')['controlActive'] and not call('act.status')['after']['usingItem'])
            at('occluded');select(2);r=call('act.combat',ticks=25,stopWhenClear=False)
            check('combat does not hit through wall',r['attackAttempts']==0,r)
            combat_fixture=at('combat');passive_health=next(e for e in combat_fixture['entities'] if e['type']=='Cow')['health']
            enemy_health=next(e for e in combat_fixture['entities'] if e['type']=='Zombie')['health']
            select(2);r=call('act.combat',ticks=80,stopWhenClear=False)
            check('bounded hostile combat attacks',r['attackAttempts']>0,r)
            observed_entities=s.call('dev.interaction_fixture.status')['entities'];evidence['combatEntities']=observed_entities
            check('passive entity excluded',next(e for e in observed_entities if e['type']=='Cow')['health']==passive_health,observed_entities)
            check('native combat causes observed damage',r['observedDeaths']>0 or any(e['type']=='Zombie' and e['health']<enemy_health for e in observed_entities),observed_entities)
            check('bounded combat releases controls',not call('obs.player')['controlActive'])
            at('chest');select(8)
            queries={'player':{'method':'obs.player'},'entities':{'method':'obs.entities','params':{'radius':8}}}
            sup.add('notify',{'queries':queries,'condition':{'all':[{'gt':['player.health',0]},{'eq':['player.controlActive',False]}]},'effects':['notify']})
            e=fired('notify');check('notify leaves time running',not call('time.status')['clientPaused'] and not e['data']['receipt']['latched'])
            future=pool.submit(c.call,'act.input',keys=['sneak'],ticks=200)
            time.sleep(.15)
            hp=call('obs.player')['health']
            sup.add('hurt',{'queries':queries,'condition':{'lt':['player.health',hp]},'effects':['notify','cancel','pause'],'reason':'health changed during test'})
            time.sleep(.2);s.call('dev.interaction_fixture.hurt',amount=1);e=fired('hurt')
            receipt=e['data']['receipt'];check('autonomous cancellation and settled pause',receipt['cancelled'] and receipt['pauseConfirmed'],receipt)
            try:future.result(4)
            except BridgeError:check('active action interrupted',True)
            else:raise AssertionError('active action was not cancelled')
            check('custom pause reason propagated',call('time.status')['state']['reason']=='interrupt:health changed during test')
            refuse('latch blocks stale new work','act.look',yaw=0,pitch=0)
            duplicate=call('interrupt.fire',eventId=receipt['eventId'],reason='ignored duplicate',effects=['cancel'],expectedContext={})
            check('reaction retry idempotent',duplicate==receipt)
            call('interrupt.ack',eventId=receipt['eventId']);check('ack does not resume',call('time.status')['clientPaused'])
            stale=dict(receipt['context'],bridgeId='stale');refuse('stale bridge reaction refused','interrupt.fire',eventId=str(uuid.uuid4()),reason='late',effects=['cancel'],expectedContext=stale)
            cursor=sup.events()['cursor'];custom=Path(tmp)/'custom.py'
            custom.write_text("def evaluate(context):\n    p=context.read('obs.player')\n    return {'match': p['health'] > 0 and context.values['player']['food'] > 0, 'payload': {'selected': p['selectedSlot']}}\n")
            sup.add('custom',{'file':str(custom),'queries':queries,'effects':['notify']})
            async def model():await asyncio.sleep(10);return 'obsolete'
            raced=asyncio.run(race_interrupt(sup,model(),cursor=cursor))
            check('custom primitive composition interrupts inference awaitable',raced['interrupted'],raced)
            check('journal replay is non-consuming',sup.events()==sup.events())
            evidence['ok']=True
        finally:
            sup.close()
            try:
                for e in c.call('interrupt.status')['latched']:c.call('interrupt.ack',eventId=e)
                call('act.stop');call('time.pause')
                if created:
                    restored=s.call('dev.interaction_fixture.restore');evidence['restored']=restored
                    time.sleep(.2);restored_player=next(p for p in s.call('obs.players') if p['name']==USER)
                    check('original player health restored',restored_player['health']==base['health'],restored_player)
                    check('client health synced while paused',call('obs.player')['health']==base['health'])
            except Exception as cleanup:
                evidence['cleanupError']=str(cleanup);evidence['ok']=False
                print('Cleanup needs journal recovery: '+str(cleanup),flush=True)
            finally:
                path=ROOT/'.runtime/logs/interaction-smoke.json';path.write_text(json.dumps(evidence,indent=2,default=str))
                print(path,flush=True)
    return 0 if evidence['ok'] else 1

if __name__=='__main__':raise SystemExit(main())
