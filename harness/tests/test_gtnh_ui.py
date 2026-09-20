# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Failure/cancellation-sensitive composition behavior, without a crafting layout."""
from copy import deepcopy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'mcp'))
import mbtool  # noqa: E402,F401
from mbtools_gtnh.inventory import ContainerSession, ProcedureStopped
from kernel import BridgeError


class FakeKernel:
    def __init__(self):
        self.view=dict(open=True,windowId=7,epoch=100,slots=[
            dict(i=71,stack=dict(id='mod:ingredient',meta=42,nbt_hash='variant-a',count=8)),
            dict(i=29,stack=None)])
        self.calls=[]
        self.result=dict(state='completed',transfer=dict(moved=3),serverAcknowledged=True)
        self.failure=None
    def call(self,method,**params):
        self.calls.append((method,params))
        if method=='obs.container':return deepcopy(self.view)
        if self.failure:raise self.failure
        return deepcopy(self.result)


class CompositionTests(unittest.TestCase):
    def test_arbitrary_layout_and_native_guards(self):
        k=FakeKernel();session=ContainerSession(k)
        session.transfer(71,[29],3,'consuming')
        method,params=k.calls[-1]
        self.assertEqual(method,'gui.transfer')
        self.assertEqual(params,dict(windowId=7,epoch=100,expectedCursor=None,source=71,
            expected=k.view['slots'][0]['stack'],destinations=[29],count=3,destinationPolicy='consuming'))
        k.view['slots'][0]['stack']['count']=5
        session.click(71)
        self.assertEqual(k.calls[-1][1]['expected']['count'],5)
    def test_new_screen_reusing_same_window_id_stops_before_mutation(self):
        k=FakeKernel();session=ContainerSession(k);k.view['epoch']+=1
        with self.assertRaises(ProcedureStopped):session.transfer(71,[29],3)
        self.assertTrue(all(m=='obs.container' for m,_ in k.calls))
    def test_partial_quantity_retains_receipt_without_retry(self):
        k=FakeKernel();session=ContainerSession(k);k.result['transfer']['moved']=1
        with self.assertRaises(ProcedureStopped) as caught:session.transfer(71,[29],3)
        self.assertEqual(len(caught.exception.receipts),1)
        self.assertEqual(sum(m=='gui.transfer' for m,_ in k.calls),1)
    def test_uncertain_failure_keeps_prior_and_partial_effects(self):
        k=FakeKernel();session=ContainerSession(k);session.transfer(71,[29],3)
        k.failure=BridgeError('ack_timeout','inspect before retrying',reply={'error':{'receipt':{'cursorAfter':{'id':'mod:ingredient'}}}})
        with self.assertRaises(ProcedureStopped) as caught:session.click(71)
        self.assertEqual(len(caught.exception.receipts),2)
        self.assertEqual(caught.exception.receipts[-1]['failed'],k.failure.reply)
        self.assertEqual(sum(m=='gui.click_slot' for m,_ in k.calls),1)
    def test_wait_uses_adapter_predicate_without_repeating_inputs(self):
        k=FakeKernel();session=ContainerSession(k)
        def completed(view):return view['slots'][1].get('stack',{}).get('id')=='mod:output' if view['slots'][1].get('stack') else False
        def process(_):k.view['slots'][1]['stack']=dict(id='mod:output',count=1)
        with patch('mbtools_gtnh.inventory.time.sleep',side_effect=process):
            result=session.wait_for(completed,timeout_s=2)
        self.assertEqual(result['slots'][1]['stack']['count'],1)
        self.assertTrue(all(m=='obs.container' for m,_ in k.calls))
    def test_postcondition_timeout_does_not_repeat_inputs(self):
        k=FakeKernel();session=ContainerSession(k)
        with patch('mbtools_gtnh.inventory.time.monotonic',side_effect=[0,2]):
            with self.assertRaises(ProcedureStopped):session.wait_for(lambda v:False,timeout_s=1)
        self.assertTrue(all(m=='obs.container' for m,_ in k.calls))


if __name__=='__main__':unittest.main()
