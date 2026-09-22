# SPDX-License-Identifier: LGPL-3.0-or-later
from copy import deepcopy
from pathlib import Path
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcp'))
import mbtool  # noqa: E402,F401
from mbtools_gtnh import inventory


class ChestKernel:
    """A 3-slot chest (slot 1 holds coal) over a player with a pick on the hotbar, dirt, dye and one empty slot."""
    def __init__(self, cls='net.minecraft.inventory.ContainerChest'):
        self.cls, self.open, self.calls = cls, False, []
        self.slots = [dict(i=0, kind='container'), dict(i=1, kind='container', stack=dict(id='coal', meta=0, count=8)), dict(i=2, kind='container'),
                      dict(i=3, kind='main', stack=dict(id='dirt', meta=0, count=64)), dict(i=4, kind='main', stack=dict(id='dye', meta=3, count=5)),
                      dict(i=5, kind='main'), dict(i=6, kind='hotbar', stack=dict(id='pick', meta=0, count=1))]

    def call(self, method, **p):
        self.calls.append((method, p))
        if method == 'obs.container': return deepcopy(dict(open=self.open, windowId=1, epoch=1, slots=self.slots, **{'class': self.cls}))
        if method in ('act.use_block', 'gui.open_inventory'): self.open = True; return {}
        if method == 'gui.close': self.open = False; return {}
        slot = self.slots[p['slot']]; stack = slot.pop('stack')
        if p['type'] == 'quick_move':  # to the first empty slot of the other side; stays put when there is none
            side = ('main', 'hotbar') if slot['kind'] == 'container' else ('container',)
            (next((s for s in self.slots if s['kind'] in side and not s.get('stack')), None) or slot)['stack'] = stack
        return dict(state='completed')


class MoveItemsTests(unittest.TestCase):
    def run_tool(self, k, **kw):
        with mock.patch.object(inventory, 'kernel', lambda: k): return inventory.mb_move_items(**kw)

    def test_put_all_spares_hotbar_and_keep_then_takes_and_closes(self):
        k = ChestKernel(); out = self.run_tool(k, at=[1, 64, 1], put='all', keep=[dict(id='dye')], take=[dict(id='coal', count=4)])
        self.assertEqual(out['put'], [dict(id='dirt', meta=0, count=64)])
        self.assertEqual(out['took'], [dict(id='coal', meta=0, count=8)])  # whole stacks: at least the count asked
        self.assertEqual(k.slots[6]['stack']['id'], 'pick'); self.assertEqual(k.slots[4]['stack']['id'], 'dye')
        self.assertEqual(out['free'], dict(you=1, there=2)); self.assertFalse(k.open)

    def test_what_finds_no_room_is_reported_not_raised(self):
        k = ChestKernel()
        for s in k.slots[:3]: s['stack'] = dict(id='stone', meta=0, count=64)
        out = self.run_tool(k, at=[1, 64, 1], put=[dict(id='dirt')])
        self.assertEqual((out['put'], out['unmoved']), ([], [dict(id='dirt', meta=0, count=64)]))

    def test_drop_works_in_your_own_inventory_and_put_there_is_refused(self):
        k = ChestKernel('net.minecraft.inventory.ContainerPlayer'); out = self.run_tool(k, drop=[dict(id='dye', meta=3)])
        self.assertEqual(out['dropped'], [dict(id='dye', meta=3, count=5)])
        self.assertIn(('gui.click_slot', 'throw', 1), [(m, p.get('type'), p.get('button')) for m, p in k.calls])
        with self.assertRaises(ValueError): self.run_tool(ChestKernel('net.minecraft.inventory.ContainerPlayer'), put='all')

    def test_drop_spares_worked_tools_unless_named_and_says_so(self):
        k = ChestKernel('net.minecraft.inventory.ContainerPlayer')
        k.slots[5]['stack'] = dict(id='dye', meta=3, count=1, name='Lucky Dye', nbt='{display:{Name:"Lucky Dye"}}', nbt_hash='h1')
        k.slots[6]['stack'] = dict(id='dye', meta=3, count=1, name='Worn Dye', dmg=[5, 100])
        out = self.run_tool(k, drop=[dict(id='dye', meta=3)])
        self.assertEqual(out['dropped'], [dict(id='dye', meta=3, count=5)])
        self.assertEqual([(x['name'], x['why']) for x in out['skipped']], [('Lucky Dye', 'carries NBT'), ('Worn Dye', 'damaged')])
        out = self.run_tool(k, drop=[dict(name='lucky')])  # by display name, any case
        self.assertEqual((out['dropped'], out['skipped']), ([dict(id='dye', meta=3, count=1)], []))
        out = self.run_tool(k, drop=[dict(id='dye', withNbt=True)])
        self.assertEqual(out['dropped'], [dict(id='dye', meta=3, count=1)])

    def test_threat_refusal_is_a_fact_with_an_override(self):
        k = ChestKernel(); threat = dict(type='Zombie', distance=5)
        call = k.call; k.call = lambda method, **p: {'state': {'threats': [threat]}} if method == 'time.status' else call(method, **p)
        with self.assertRaises(ValueError) as refused: self.run_tool(k, at=[1, 64, 1], put='all')
        self.assertEqual(refused.exception.receipts[0]['refused'], dict(action='open a GUI', reason='no_threat', threats=[threat], override='despiteThreat'))
        self.assertEqual(self.run_tool(k, at=[1, 64, 1], put='all', despite_threat=True)['put'], [dict(id='dirt', meta=0, count=64), dict(id='dye', meta=3, count=5)])


if __name__ == '__main__':
    unittest.main()
