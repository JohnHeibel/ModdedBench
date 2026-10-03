# SPDX-License-Identifier: LGPL-3.0-or-later
from copy import deepcopy
from pathlib import Path
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcp'))
import mbtool  # noqa: E402,F401
from mbtools_gtnh import inventory


class PlayerKernel:
    """36 player slots (0..8 hotbar) as the bridge reports them; the inventory GUI lists them from i=9 (after crafting and armour)."""
    def __init__(self, stacks, selected=0):
        self.inv, self.selected, self.open, self.calls = [None] * 36, selected, False, []
        for i, stack in stacks.items(): self.inv[i] = stack

    def call(self, method, **p):
        self.calls.append((method, p))
        if method == 'obs.inventory':
            main = [dict(slot=i, kind='hotbar' if i < 9 else 'main', stack=deepcopy(s)) for i, s in enumerate(self.inv)]
            return dict(main=main, selected=self.selected, held=deepcopy(self.inv[self.selected]), cursor=None)
        if method == 'obs.container':
            slots = [dict(i=9 + n, idx=n, kind='hotbar' if n < 9 else 'main', stack=deepcopy(s)) for n, s in enumerate(self.inv)]
            return dict(open=self.open, windowId=0, epoch=1, slots=slots, cursor=None, **{'class': 'net.minecraft.inventory.ContainerPlayer'})
        if method == 'gui.open_inventory': self.open = True; return {}
        if method == 'gui.close': self.open = False; return {}
        if method == 'act.select_hotbar': self.selected = p['slot']; return dict(state='completed')
        if method == 'gui.click_slot' and p['type'] == 'swap':
            n, b = p['slot'] - 9, p['button']; self.inv[n], self.inv[b] = self.inv[b], self.inv[n]
            return dict(state='completed')
        raise AssertionError(method)


SWORD, DIRT, TORCH = dict(id='sword', meta=0, count=1), dict(id='dirt', meta=0, count=64), dict(id='torch', meta=0, count=16)


class HoldTests(unittest.TestCase):
    def hold(self, k, **kw):
        with mock.patch.object(inventory, 'kernel', lambda: k): return inventory.mb_hold(**kw)

    def test_on_the_hotbar_it_only_selects(self):
        k = PlayerKernel({0: DIRT, 4: SWORD})
        self.assertEqual(self.hold(k, item=dict(id='sword')), dict(held=SWORD, slot=4))
        self.assertNotIn('gui.open_inventory', [m for m, _ in k.calls])

    def test_from_the_inventory_it_goes_to_an_empty_hotbar_slot_and_the_gui_closes(self):
        k = PlayerKernel({0: DIRT, 20: SWORD})
        self.assertEqual(self.hold(k, item=dict(id='sword')), dict(held=SWORD, slot=1))
        self.assertEqual((k.inv[0], k.inv[20], k.open), (DIRT, None, False))

    def test_a_full_hotbar_swaps_with_the_selected_slot(self):
        k = PlayerKernel({**{i: TORCH for i in range(9)}, 30: SWORD}, selected=3)
        self.assertEqual(self.hold(k, item=dict(id='sword')), dict(held=SWORD, slot=3))
        self.assertEqual(k.inv[30], TORCH)

    def test_an_empty_hand_takes_it_in_place_and_slot_overrides(self):
        k = PlayerKernel({0: DIRT, 20: SWORD}, selected=2)
        self.assertEqual(self.hold(k, item=dict(id='sword'))['slot'], 2)
        k = PlayerKernel({0: DIRT, 4: SWORD})
        self.assertEqual(self.hold(k, item=dict(id='sword'), slot=6), dict(held=SWORD, slot=6))
        self.assertIsNone(k.inv[4])

    def test_missing_item_and_bad_slot_are_refused(self):
        with self.assertRaises(ValueError): self.hold(PlayerKernel({0: DIRT}), item=dict(id='sword'))
        with self.assertRaises(ValueError): self.hold(PlayerKernel({0: SWORD}), item=dict(id='sword'), slot=9)
        with self.assertRaises(ValueError): self.hold(PlayerKernel({0: SWORD}), item=dict(meta=0))

    def test_empty_hand_selects_a_free_hotbar_slot(self):
        k = PlayerKernel({0: SWORD})
        self.assertEqual(self.hold(k), dict(held=None, slot=1))
        self.assertEqual(k.inv[0], SWORD)
        self.assertNotIn('gui.open_inventory', [m for m, _ in k.calls])

    def test_empty_hand_parks_a_full_hotbars_selected_stack(self):
        k = PlayerKernel({i: TORCH for i in range(9)}, selected=3)
        self.assertEqual(self.hold(k, item=None), dict(held=None, slot=3))
        self.assertEqual((k.inv[9], k.open), (TORCH, False))

    def test_empty_hand_refuses_full_inventory_or_open_gui(self):
        with self.assertRaises(ValueError): self.hold(PlayerKernel({i: DIRT for i in range(36)}))
        k = PlayerKernel({0: SWORD}); k.open = True
        with self.assertRaisesRegex(ValueError, 'close the GUI'): self.hold(k)
        self.assertEqual(k.inv[0], SWORD)
        with self.assertRaisesRegex(ValueError, 'omit slot'): self.hold(PlayerKernel({}), slot=2)

    def test_empty_hand_requires_observed_selection(self):
        k = PlayerKernel({0: SWORD})
        original = k.call
        def ignored_select(method, **p):
            if method == 'act.select_hotbar': return dict(state='completed')
            return original(method, **p)
        k.call = ignored_select
        with self.assertRaisesRegex(ValueError, 'not observed'): self.hold(k)


if __name__ == '__main__':
    unittest.main()
