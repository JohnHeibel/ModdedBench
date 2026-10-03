# SPDX-License-Identifier: LGPL-3.0-or-later
from copy import deepcopy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcp'))
import mbtool  # noqa: E402,F401
from mbtools_gtnh import inventory


class MachineKernel:
    def __init__(self, replacement=None, blocked=False):
        self.replacement, self.blocked = replacement, blocked
        self.slots = [dict(i=0, kind='container', ordinary=True, canTake=True,
                           stack=dict(id='example:output', meta=0, count=1)),
                      dict(i=1, kind='main', ordinary=True, canTake=True)]

    def call(self, method, **params):
        if method == 'obs.container':
            slots = deepcopy(self.slots)
            if 'probeSlot' in params:
                slots[0]['acceptsProbe'] = False
            return dict(open=True, windowId=1, epoch=1, slots=slots)
        if method == 'gui.click_slot':
            if not self.blocked:
                self.slots[1]['stack'] = self.slots[0].pop('stack')
                if self.replacement:
                    self.slots[0]['stack'] = self.replacement
            return dict(state='completed')
        raise AssertionError(method)


class MachineCollectionTests(unittest.TestCase):
    def test_collection_survives_same_count_replacement(self):
        for item in ('example:output', 'example:other'):
            with self.subTest(item=item):
                k = MachineKernel(dict(id=item, meta=0, count=1))
                result = inventory._machine(inventory.ContainerSession(k), [], 0)
                self.assertEqual(result['collected'], [dict(id='example:output', meta=0, count=1)])
                self.assertEqual(result['inside'], [dict(slot=0, id=item, meta=0, count=1)])

    def test_failed_click_reports_no_inventory_gain(self):
        k = MachineKernel(blocked=True)
        with self.assertRaisesRegex(inventory.ProcedureStopped, 'did not reach your inventory'):
            inventory._machine(inventory.ContainerSession(k), [], 0)


if __name__ == '__main__':
    unittest.main()
