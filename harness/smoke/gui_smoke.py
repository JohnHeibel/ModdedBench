# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Batched native inventory/UI acceptance in the journaled development fixture."""
from __future__ import annotations
import json
from pathlib import Path
import sys
import time
from concurrent.futures import ThreadPoolExecutor

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'harness' / 'mcp'))
from kernel import Kernel, BridgeError, bridge_url
import mbtool  # noqa: F401  (installs the mbtools_gtnh package)
from mbtools_gtnh.inventory import ContainerSession


def main():
    evidence = dict(ok=False, checks=[], views={}, receipts=[])
    created = False
    def check(name, ok, detail=None):
        if not ok:
            raise AssertionError(f'{name}: {detail}')
        evidence['checks'].append(name)
        print(name, flush=True)

    with Kernel() as c, Kernel(url=bridge_url('server')) as s:
        def view(): return c.call('obs.container', detail='full')
        def slot(v, index): return next(e for e in v['slots'] if e['i'] == index)
        def count(v, index): return (slot(v, index).get('stack') or {}).get('count', 0)
        def source(v, item, meta=0, name=None):
            return next(e['i'] for e in v['slots'] if e['kind'] in ('main', 'hotbar')
                        and (e.get('stack') or {}).get('id') == item and e['stack']['meta'] == meta
                        and (e['stack'].get('name') == name if name else not e['stack'].get('nbt')))
        def call(method, **params):
            r = c.call(method, **params);evidence['receipts'].append(r);return r
        def click(index, **params):
            v = view()
            return call('gui.click_slot', windowId=v['windowId'], epoch=v['epoch'], slot=index,
                        expected=slot(v, index).get('stack'), expectedCursor=v.get('cursor'), **params)
        def transfer(src, dest, quantity, **params):
            v = view()
            return call('gui.transfer', windowId=v['windowId'], epoch=v['epoch'], source=src,
                        expected=slot(v, src)['stack'], destinations=dest, count=quantity, **params)
        def returned(dest):
            v=view()
            return call('gui.return_cursor', windowId=v['windowId'], epoch=v['epoch'], expectedCursor=v.get('cursor'), destinations=dest)
        def refuse(name, method, **params):
            try:
                c.call(method, **params)
            except BridgeError as error:
                evidence['receipts'].append(error.reply)
                check(name, error.code in ('bad_request', 'gui_error'), str(error))
            else: raise AssertionError(name+': unexpectedly succeeded')
        def open_at(name):
            c.call('gui.close')
            s.call('dev.gui_fixture.position', name=name)
            time.sleep(.5)
            c.call('act.look', yaw=-90, pitch=29)
            c.call('act.input', keys=['use'], ticks=1)
            deadline=time.monotonic()+8
            while time.monotonic()<deadline:
                v=view()
                if v['open'] and v['windowId'] != 0: break
                time.sleep(.2)
            evidence['views'][name]=v
            check(name+'_native_open', v['open'] and v['windowId'] != 0, v)
            check(name+'_slot_geometry', all('clickAt' in e for e in v['slots']), v.get('class'))
            return v
        def server_agrees(name):
            v=view();sv=s.call('dev.gui_fixture.status')
            def identity(st): return None if not st else (st['id'],st['meta'],st['count'],st.get('nbt'))
            check(name, sv['windowId']==v['windowId'] and
                  [identity(e.get('stack')) for e in sv['slots']]==[identity(e.get('stack')) for e in v['slots']]
                  and identity(sv.get('cursor'))==identity(v.get('cursor')), sv)

        try:
            c.call('time.configure', healthDrop=False, healthBelow=-1, airBelow=-1, actionFailed=False, pauseOnDisconnect=True)
            c.call('time.pause')
            created=True
            evidence['fixture']=s.call('dev.gui_fixture.create')
            c.call('time.resume')
            time.sleep(1)
            v=open_at('chest')
            c.call('time.pause')
            refuse('paused_gui_mutation_refused','gui.click_slot',windowId=v['windowId'],epoch=v['epoch'],slot=0)
            check('paused_gui_observation_available',view()['epoch']==v['epoch'])
            c.call('time.resume')
            paper=source(v,'minecraft:paper')
            result=transfer(paper,[0],7)
            check('exact_count_acknowledged', result['serverAcknowledged'] and result['transfer']['moved']==7, result)
            check('exact_count_server_state', count(s.call('dev.gui_fixture.status'),0)==7)
            v=view()
            refuse('stale_source_refused','gui.transfer',windowId=v['windowId'],epoch=v['epoch'],source=paper,expected=dict(slot(v,paper)['stack'],count=99),destinations=[1],count=1)
            refuse('stale_epoch_refused','gui.click_slot',windowId=v['windowId'],epoch=v['epoch']-1,slot=0)
            refuse('fractional_epoch_refused','gui.click_slot',windowId=v['windowId'],epoch=v['epoch']+.5,slot=0)
            refuse('invalid_settle_refused_before_click','gui.click_slot',windowId=v['windowId'],slot=0,settleTicks=0)
            check('invalid_options_preserve_cursor',not view().get('cursor'))
            a=source(v,'minecraft:paper',name='Variant A');b=source(v,'minecraft:paper',name='Variant B')
            check('nbt_hash_distinguishes_variants',slot(v,a)['stack']['nbt_hash']!=slot(v,b)['stack']['nbt_hash'])
            transfer(a,[2],2)
            result=transfer(b,[2],1)
            check('nbt_variants_do_not_merge',result['transfer']['moved']==0 and not result['attempted'],result)
            exact=c.call('obs.find',selector={'id':'minecraft:paper','nbt_hash':slot(view(),a)['stack']['nbt_hash']},scope='container')
            check('exact_nbt_selector',len(exact['matches'])==2,exact)
            check('native_tooltip',bool(c.call('obs.tooltip',slot=2)['lines']))
            w1=source(view(),'minecraft:wool',1);w2=source(view(),'minecraft:wool',2)
            transfer(w1,[3],2)
            check('metadata_does_not_merge',transfer(w2,[3],1)['transfer']['moved']==0)
            cobble=source(view(),'minecraft:cobblestone')
            transfer(cobble,[4],62)
            check('partial_capacity_receipt',transfer(cobble,[4],8)['transfer']['moved']==2)
            result=click(0)
            check('native_event_pickup_acknowledged',result['eventsDelivered']>=2 and result['serverAcknowledged'],result)
            refuse('occupied_cursor_close_refused','gui.close')
            returned([0]);server_agrees('cursor_return_synchronized')
            click(0,type='quick_move')
            check('shift_modifier_reaches_container',count(view(),0)==0)
            check('hotbar_swap_acknowledged',click(paper,type='swap',button=8)['serverAcknowledged'])
            click(paper,type='swap',button=8)
            paper=source(view(),'minecraft:paper')
            click(paper)
            v=view();points=[slot(v,i)['clickAt'] for i in (5,6,7)]
            result=call('gui.drag',epoch=v['epoch'],points=points,button=0)
            check('drag_native_distribution',all(count(view(),i)>0 for i in (5,6,7)),result)
            if view().get('cursor'): returned([paper])
            server_agrees('drag_server_agrees')
            v=view()
            result=call('gui.scroll',epoch=v['epoch'],x=v['gui']['left']+20,y=v['gui']['top']+15,delta=120)
            check('wheel_native_delivery',result['eventsDelivered']>0,result)
            check('slot_hit_test',bool(c.call('gui.hit_test',**slot(v,5)['clickAt'])))
            click(5,type='quick_move')
            v=view()
            with ThreadPoolExecutor(max_workers=1) as pool:
                held=pool.submit(c.call,'gui.click_at',epoch=v['epoch'],**slot(v,8)['clickAt'],holdTicks=80,shift=True)
                time.sleep(.3)
                c.call('act.stop')
                try:held.result(timeout=5)
                except BridgeError as error:check('held_click_cancelled',error.code=='cancelled',str(error))
                else:raise AssertionError('held click was not cancelled')
            check('cancel_releases_gui_owner',not c.call('obs.player')['controlActive'])
            paper=source(view(),'minecraft:paper');click(paper)
            check('cancel_clears_shift_modifier',bool(view().get('cursor')))
            returned([paper])

            # Native text-field callbacks are essential: direct setText would skip the rename packet.
            v=open_at('anvil');paper=source(v,'minecraft:paper')
            transfer(paper,[0],1)
            v=view();evidence['views']['anvil_loaded']=v
            field=next(f for f in v['fields'] if 'focused' in f and 'max' in f)
            result=call('gui.text_field',epoch=v['epoch'],field=field['field'],text='Native UI renamed',clear=True)
            deadline=time.monotonic()+3
            while time.monotonic()<deadline and (slot(view(),2).get('stack') or {}).get('name')!='Native UI renamed':time.sleep(.1)
            check('anvil_listener_updates_server_output',(slot(s.call('dev.gui_fixture.status'),2).get('stack') or {}).get('name')=='Native UI renamed',view())
            click(2)
            target=next(e['i'] for e in view()['slots'] if e['kind']=='main' and not e.get('stack'))
            returned([target]);server_agrees('anvil_output_server_agrees')

            v=open_at('furnace')
            pork=source(v,'minecraft:porkchop');coal=source(v,'minecraft:coal')
            transfer(pork,[0],2,destinationPolicy='consuming')
            result=transfer(coal,[1],1,destinationPolicy='consuming')
            check('consuming_fuel_receipt',result['serverAcknowledged'] and result['transfer']['moved']==1,result)

            # Same composition primitive across unrelated container layouts.
            recipes=c.call('nei.recipes',id='minecraft:carpet',meta=1,handler='Shaped Crafting',limit=20,alternativesLimit=1)
            recipe=next(r for r in recipes['recipes'] if len(r['inputs'])==2 and all(i['alternatives'][0]['id']=='minecraft:wool' for i in r['inputs']))
            evidence['crafting_recipe']=recipe
            for name in ('crafting','tinkers','gregtech','ae2','forestry'):
                v=open_at(name)
                if name in ('crafting','tinkers','forestry'):
                    src=source(v,'minecraft:planks')
                    candidates=[e['i'] for e in v['slots'] if e['kind']=='container' and e.get('ordinary') and e['canTake'] and not e.get('stack')]
                    destination=1 if name in ('crafting','tinkers') else candidates[-1]
                    session=ContainerSession(c)
                    session.transfer(src,[destination],1)
                    check(name+'_composed_load',count(view(),destination)==1)
                    # Removal is a native click, also valid for output/custom slot semantics.
                    session.click(destination)
                    returned([src]);server_agrees(name+'_server_agrees')
                    if name in ('crafting','tinkers'):
                        wool=source(view(),'minecraft:wool',1)
                        for destination in (1,2):session.transfer(wool,[destination],1)
                        expected=recipe['result']['alternatives'][0]
                        session.wait_for(lambda current:(slot(current,0).get('stack') or {}).get('id')==expected['id'],timeout_s=3)
                        session.click(0)
                        check(name+'_native_crafting_output',(view().get('cursor') or {}).get('count')==expected['count'],view().get('cursor'))
                        check(name+'_ingredients_consumed',count(view(),1)==0 and count(view(),2)==0)
                        empty=next(e['i'] for e in view()['slots'] if e['kind']=='main' and not e.get('stack'))
                        returned([empty]);server_agrees(name+'_crafted_server_agrees')
                elif name=='ae2':
                    check('ae2_virtual_slots_observed',any(not e['ordinary'] for e in v['slots']),v['slots'])
                    cell=next(e['i'] for e in v['slots'] if (e.get('stack') or {}).get('id')=='appliedenergistics2:item.ItemBasicStorageCell.1k')
                    # This slot initializes cell NBT on insertion. Exact identity transfer
                    # must report that partial effect, not retry or label it a passive move.
                    try:transfer(cell,[0],1)
                    except BridgeError as error:
                        evidence['receipts'].append(error.reply)
                        receipt=(error.reply or {}).get('error',{}).get('receipt',{})
                        check('ae2_nbt_change_partial_receipt',receipt.get('attempted') and receipt.get('changes'),receipt)
                    else:raise AssertionError('expected AE2 cell initialization to change NBT')
                    time.sleep(.3)
                    check('ae2_partial_effect_inspected_without_retry',(slot(s.call('dev.gui_fixture.status'),0).get('stack') or {}).get('id')=='appliedenergistics2:item.ItemBasicStorageCell.1k' and not view().get('cursor'))
                    paper=source(view(),'minecraft:paper');click(paper)
                    v=view();cursor=v['cursor'];ghost=next(e['i'] for e in v['slots'] if not e['ordinary'])
                    refuse('ae2_structured_ghost_refused','gui.click_slot',windowId=v['windowId'],epoch=v['epoch'],slot=ghost,path='structured')
                    result=click(ghost)
                    check('ae2_native_ghost_keeps_cursor',view().get('cursor')==cursor,result)
                    check('ae2_filter_set_on_server',(slot(s.call('dev.gui_fixture.status'),ghost).get('stack') or {}).get('id')=='minecraft:paper',view())
                    returned([paper])
                    click(ghost,button=0)
                    check('ae2_ghost_clear',not slot(s.call('dev.gui_fixture.status'),ghost).get('stack'))
                else:
                    check('gregtech_native_widget_tree',len(v.get('modularUi',{}).get('widgets',[]))>10,v.get('modularUi'))
                    src=source(v,'minecraft:planks')
                    probe=c.call('obs.container',probeSlot=src)
                    destination=next(e['i'] for e in probe['slots'] if e['kind']=='container' and e['ordinary'] and e['acceptsProbe'] and e['spaceForProbe']>0)
                    transfer(src,[destination],1)
                    click(destination)
                    check('gregtech_native_slot_event',bool(view().get('cursor')))
                    returned([src]);server_agrees('gregtech_server_agrees')
            evidence['ok']=True
        except BaseException as error:
            evidence['error']=repr(error)
            try:evidence['failure_view']=view();evidence['failure_server']=s.call('dev.gui_fixture.status');evidence['last_action']=c.call('gui.status')
            except Exception:pass
            raise
        finally:
            if created:
                c.call('act.stop')
                # Explicit fixture restoration recovers the journaled inventory even after cursor failures.
                evidence['restore']=s.call('dev.fluid_fixture.restore',timeout=30)
            evidence['time']=c.call('time.pause',timeout=30)
            path=ROOT/'.runtime/gui-smoke.json'
            path.write_text(json.dumps(evidence,indent=2),encoding='utf-8')
            print(path,flush=True)


if __name__=='__main__':main()
