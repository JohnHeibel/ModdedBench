# SPDX-License-Identifier: LGPL-3.0-or-later
from copy import deepcopy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcp'))
import mbtool  # noqa: E402,F401
from mbtools_gtnh.inventory import _empty_hand, _station


class HandKernel:
    def __init__(self, empty_hotbar=False, full=False, reject=False):
        self.selected, self.open, self.calls, self.reject = 2, False, [], reject
        self.main = [dict(slot=i, kind='hotbar' if i < 9 else 'main',
                          stack=dict(id='item', count=1)) for i in range(36)]
        if not full: self.main[5 if empty_hotbar else 15]['stack'] = None

    def call(self, method, **params):
        self.calls.append((method, params))
        if method == 'obs.inventory':
            return deepcopy(dict(main=self.main, selected=self.selected, held=self.main[self.selected]['stack']))
        if method == 'obs.container':
            return deepcopy(dict(open=self.open, windowId=0, epoch=1,
                                 slots=[dict(s, i=s['slot'] + 9, idx=s['slot']) for s in self.main]))
        if method == 'gui.open_inventory': self.open = True
        elif method == 'act.use_block':
            assert self.main[self.selected]['stack'] is None
            self.open = True
        elif method == 'time.status': return dict(state=dict(threats=[]))
        elif method == 'gui.close': self.open = False
        elif method == 'act.select_hotbar':
            if not self.reject: self.selected = params['slot']
        elif method == 'gui.click_slot':
            assert params['type'] == 'swap'
            assert params['expectedCursor'] is None
            src, dst = self.main[params['slot'] - 9], self.main[params['button']]
            assert params['expected'] == src['stack']
            if not self.reject: src['stack'], dst['stack'] = dst['stack'], src['stack']
        else: raise AssertionError(method)
        return dict(state='completed')


class EmptyHandTests(unittest.TestCase):
    def test_existing_empty_hotbar_is_selected(self):
        k = HandKernel(empty_hotbar=True)
        _empty_hand(k)
        self.assertEqual(k.selected, 5)
        self.assertFalse(k.open)
        self.assertFalse(any(m == 'gui.open_inventory' for m, _ in k.calls))

    def test_full_hotbar_moves_held_stack_to_an_empty_main_slot(self):
        k = HandKernel()
        _empty_hand(k)
        self.assertIsNone(k.main[2]['stack'])
        self.assertEqual(k.main[15]['stack'], dict(id='item', count=1))
        self.assertFalse(k.open)

    def test_full_inventory_refuses_without_mutating(self):
        k = HandKernel(full=True)
        with self.assertRaisesRegex(ValueError, 'empty hand'): _empty_hand(k)
        self.assertEqual([m for m, _ in k.calls], ['obs.inventory'])

    def test_station_restores_parked_stack_before_container_operations(self):
        k = HandKernel()
        before = deepcopy(k.main)
        self.assertTrue(_station(k, [1, 2, 3]))
        self.assertTrue(k.open)
        self.assertEqual(k.main, before)
        self.assertEqual(sum(m == 'act.use_block' for m, _ in k.calls), 1)

    def test_unconfirmed_selection_or_swap_prevents_block_click(self):
        for empty_hotbar in (False, True):
            k = HandKernel(empty_hotbar=empty_hotbar, reject=True)
            with self.assertRaisesRegex(ValueError, 'not observed'): _empty_hand(k)
            self.assertFalse(any(m == 'act.use_block' for m, _ in k.calls))
