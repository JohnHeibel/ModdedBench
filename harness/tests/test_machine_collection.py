# SPDX-License-Identifier: MIT
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
    def test_an_output_that_grew_before_the_press_is_looked_at_again_and_nothing_sent_is_repeated(self):
        class GrowingKernel(MachineKernel):
            def __init__(self, msg='stale_stack before native press; virtual results may have reordered', sent=0,
                         becomes=None, grow=1, always=False, ordinary=True):
                super().__init__()
                self.msg, self.sent, self.becomes, self.grow, self.always, self.presses = msg, sent, becomes, grow, always, 0
                self.slots[0]['ordinary'] = ordinary

            def call(self, method, **params):
                if method == 'gui.click_slot':
                    self.presses += 1
                    if self.presses == 1 or self.always:
                        self.slots[0]['stack']['count'] += self.grow
                        if self.becomes:
                            self.slots[0]['stack']['id'] = self.becomes
                        error = dict(code='gui_error', msg=self.msg, receipt=dict(transactions=dict(sent=self.sent)))
                        raise inventory.BridgeError('gui_error', self.msg, method, dict(error=error))
                return super().call(method, **params)

        for msg in ('stale_stack before native press; virtual results may have reordered', 'stale_stack: observe again'):
            k = GrowingKernel(msg)
            result = inventory._machine(inventory.ContainerSession(k), [], 0)
            self.assertEqual(result['collected'], [dict(id='example:output', meta=0, count=2)])
            self.assertEqual(k.presses, 2)
        # Another item, a stack that did not grow, a sent transaction, another refusal, a virtual slot: all stop at once.
        # A slot that keeps growing stops after the third press.
        for kwargs, presses in [(dict(becomes='example:other'), 1), (dict(grow=0), 1), (dict(sent=1), 1),
                                (dict(msg='stale_cursor before native press'), 1), (dict(ordinary=False), 1),
                                (dict(always=True), 3)]:
            with self.subTest(kwargs=kwargs):
                k = GrowingKernel(**kwargs)
                with self.assertRaises(inventory.ProcedureStopped):
                    inventory._machine(inventory.ContainerSession(k), [], 0)
                self.assertEqual(k.presses, presses)

    def test_loading_distributes_one_transfer_across_native_input_capacities(self):
        class LoadingKernel:
            def __init__(self):
                self.transfers = []
                self.slots = [dict(i=i, kind='container', ordinary=True, canTake=True) for i in range(3)]
                self.slots.append(dict(i=3, kind='main', ordinary=True, canTake=True,
                                       stack=dict(id='example:input', meta=0, count=5)))

            def call(self, method, **params):
                if method == 'obs.container':
                    slots = deepcopy(self.slots)
                    if 'probeSlot' in params:
                        for slot, capacity in zip(slots, (1, 2, 2)):
                            slot['spaceForProbe'] = capacity - slot.get('stack', {}).get('count', 0)
                            slot['acceptsProbe'] = True
                    return dict(open=True, windowId=1, epoch=1, slots=slots)
                if method == 'gui.transfer':
                    self.transfers.append(params)
                    for slot, count in zip(self.slots, (1, 2, 2)):
                        slot['stack'] = dict(id='example:input', meta=0, count=count)
                    self.slots[3].pop('stack')
                    return dict(state='completed', transfer=dict(moved=5))
                raise AssertionError(method)

        k = LoadingKernel()
        result = inventory._machine(inventory.ContainerSession(k), [dict(id='example:input', count=5)], 0)
        self.assertEqual(len(k.transfers), 1)
        self.assertEqual(k.transfers[0]['destinations'], [0, 1, 2])
        self.assertEqual(result['loaded'], [dict(slot=i, id='example:input', count=n) for i, n in enumerate((1, 2, 2))])

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
