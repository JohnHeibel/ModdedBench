# SPDX-License-Identifier: LGPL-3.0-or-later
from copy import deepcopy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcp'))
import mbtool  # noqa: E402,F401
from mbtools_gtnh.inventory import ContainerSession, _grid


class CraftKernel:
    def __init__(self):
        self.slots = [dict(i=0, kind='container', inventory=0, slotClass='SlotCrafting')]
        self.slots += [dict(i=i, kind='container', inventory=1, slotClass='Slot') for i in range(1, 5)]
        self.slots += [dict(i=5, kind='main', inventory=2, stack=dict(id='tool', meta=24, count=1)),
                       dict(i=6, kind='main', inventory=2, stack=dict(id='clay', meta=0, count=4)),
                       dict(i=7, kind='main', inventory=2)]
        self.mutations = []

    def call(self, method, **p):
        if method == 'obs.container':
            self.slots[0].pop('stack', None)
            if self.slots[1].get('stack') and self.slots[2].get('stack'):
                self.slots[0]['stack'] = dict(id='dust', meta=0, count=1)
            return deepcopy(dict(open=True, windowId=0, epoch=1, slots=self.slots))
        self.mutations.append((method, p))
        if method == 'gui.transfer':
            src, dst = self.slots[p['source']], self.slots[p['destinations'][0]]
            dst['stack'] = dict(src['stack'], count=p['count'])
            src['stack']['count'] -= p['count']
            if not src['stack']['count']: src.pop('stack')
            return dict(state='completed', serverAcknowledged=True, transfer=dict(moved=p['count']))
        if method == 'gui.click_slot':
            if p['slot'] == 0:
                self.slots[7]['stack'] = dict(id='dust', meta=0, count=self.slots[2]['stack']['count'])
                self.slots[2].pop('stack')
                self.slots[1]['stack']['nbt_hash'] = 'used-tool'
            else:
                self.slots[5]['stack'] = self.slots[p['slot']].pop('stack')
            return dict(state='completed', serverAcknowledged=True)
        raise AssertionError(method)


class CraftGridTests(unittest.TestCase):
    def test_native_batch_with_one_retained_tool_and_cleanup(self):
        k = CraftKernel()
        result = _grid(ContainerSession(k), [[dict(id='tool', count=1), dict(id='clay')]], 4)
        self.assertEqual(result['gained'], 4)
        self.assertEqual(k.slots[5]['stack']['nbt_hash'], 'used-tool')
        self.assertFalse(any(s.get('stack') for s in k.slots[1:5]))
        self.assertEqual([p['count'] for m, p in k.mutations if m == 'gui.transfer'], [1, 4])
        self.assertEqual(sum(m == 'gui.click_slot' and p['slot'] == 0 for m, p in k.mutations), 1)

    def test_invalid_cell_count_precedes_mutation(self):
        for count in [0, 65, True, 1.5]:
            k = CraftKernel()
            with self.assertRaises(ValueError):
                _grid(ContainerSession(k), [[dict(id='tool', count=count)]], 4)
            self.assertFalse(k.mutations)


if __name__ == '__main__':
    unittest.main()
