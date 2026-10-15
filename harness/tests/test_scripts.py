# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcp'))
import mbtool
from kernel import BridgeError, Kernel, Reply, resume_once
from mbtools_gtnh import scripts


class StepKernel(Kernel):
    def __init__(self):
        self.directives = []

    def call_reply(self, method, timeout=None, **params):
        if method == 'sys.methods': return Reply(True, 0, 0, 0, [{'name': 'act.input', 'effect': 'interaction'}])
        directive = params.get('_resume')
        self.directives.append(directive)
        if not directive:
            return Reply(False, 0, 0, 0, error={'code': 'bad_request', 'msg': 'time_paused'})
        return Reply(True, 0, 0, 0, {'done': True}, raw={'resumedWorld': {'ticks': directive}})


class ScriptResumeTests(unittest.TestCase):
    def test_each_call_has_its_own_step_and_restores_outer_context(self):
        k = StepKernel()

        @mbtool.tool()
        def action():
            return k.call('act.input')
        outer = {}
        token = resume_once.set(outer)
        try:
            with mock.patch.object(scripts, '_tools', return_value={'action': scripts._resuming(action)}):
                result = scripts.mb_run(code='def main():\n return [action(resume=3), action(resume=7)]')
            self.assertEqual(k.directives, [3, 7])
            self.assertEqual([r['resumedWorld']['ticks'] for r in result['result']], [3, 7])
            self.assertIs(resume_once.get(), outer)
        finally:
            resume_once.reset(token)

    def test_failure_restores_context_and_bad_resume_is_refused(self):
        @mbtool.tool()
        def action():
            raise ValueError('held')
        token = resume_once.set(None)
        try:
            wrapped = scripts._resuming(action)
            with self.assertRaisesRegex(ValueError, 'held'):
                wrapped(resume=True)
            self.assertIsNone(resume_once.get())
            with self.assertRaisesRegex(ValueError, 'tick count'):
                wrapped(resume=-1)
        finally:
            resume_once.reset(token)

    def test_reads_and_tools_with_their_own_resume_are_left_alone(self):
        @mbtool.tool(lane='read')
        def look():
            return 1

        @mbtool.tool()
        def own(resume=False):
            return resume
        self.assertIs(scripts._resuming(look), look)
        self.assertIs(scripts._resuming(own), own)



class ScriptInterruptTests(unittest.TestCase):
    def run_script(self, code, **tools):
        with mock.patch.object(scripts, '_tools', return_value=tools):
            return scripts.mb_run(code=code)

    def test_a_latched_refusal_cannot_be_swallowed_by_except_exception(self):
        def act():
            raise BridgeError('bad_request', 'interrupt_latched: 1 delivered, this action was not started', 'act.input')
        out = self.run_script('def main():\n try:\n  act()\n except Exception:\n  return "carried on"', act=act)
        self.assertNotIn('result', out)
        self.assertEqual(out['stopped'], 'BridgeError: act.input: bad_request: interrupt_latched: 1 delivered, this action was not started')
        self.assertEqual((out['line'], out['interrupted']['tool']), (3, 'act'))

    def test_a_wrapped_pause_still_counts_and_a_bare_except_is_reported(self):
        def craft():
            try:
                raise BridgeError('cancelled', 'world paused by a rule (hostile_within): read mb_time status, decide, resume', 'gui.click_slot')
            except BridgeError as error:
                raise RuntimeError('screen changed') from error
        out = self.run_script('def main():\n try:\n  craft()\n except:\n  pass\n return 1', craft=craft)
        self.assertEqual(out['result'], 1)
        self.assertEqual(out['interrupted'], {'tool': 'craft', 'error': 'screen changed'})

    def test_a_job_a_rule_left_held_stops_the_script_and_names_the_job(self):
        calls = []

        def walk():
            calls.append('walk')
            return {'state': 'suspended', 'pausedBy': 'health_below', 'suspendedJobId': 'j1', 'suspended': 'a rule paused the world (health_below) with this job unfinished'}

        def nested():  # a tool that returns the job's receipt under a key of its own
            return {'moved': {'state': 'suspended', 'pausedBy': 'hostile_within', 'suspendedJobId': 'j2'}}
        out = self.run_script('def main():\n try:\n  walk()\n except Exception:\n  pass\n walk()\n return "carried on"', walk=walk)
        self.assertEqual((calls, out.get('result'), out['line']), (['walk'], None, 3))
        self.assertEqual({k: out['interrupted'][k] for k in ('tool', 'pausedBy', 'suspendedJobId')}, {'tool': 'walk', 'pausedBy': 'health_below', 'suspendedJobId': 'j1'})
        self.assertIn('world paused by a rule (health_below)', out['stopped'])
        self.assertEqual(self.run_script('def main():\n nested()\n return 1', nested=nested)['interrupted']['suspendedJobId'], 'j2')

    def test_a_job_a_step_left_held_does_not_stop_the_script(self):
        def walk():
            return {'state': 'suspended', 'suspendedJobId': 'j1'}
        self.assertEqual(self.run_script('def main():\n return walk()["suspendedJobId"]', walk=walk), {'result': 'j1', 'log': []})

    def test_ordinary_errors_stay_catchable(self):
        def act():
            raise ValueError('no such item')
        out = self.run_script('def main():\n try:\n  act()\n except Exception as e:\n  return str(e)', act=act)
        self.assertEqual(out, {'result': 'no such item', 'log': []})


class RuleKernel:
    """The clock's rule table and nothing else; reads of it counted."""
    def __init__(self):
        self.rules, self.reads = {'health_drop': {'name': 'health_drop', 'kind': 'health_drop'}, 'air_below': {'name': 'air_below', 'kind': 'air_below', 'level': 180},
                                  'hostile_within': {'name': 'hostile_within', 'kind': 'hostile_within', 'level': 12}}, 0

    def call(self, method, timeout=None, **p):
        if method != 'time.rules': return {}
        self.reads += not p
        for r in p.get('set', []): self.rules[r['name']] = r
        for n in p.get('remove', []): self.rules.pop(n, None)
        return {'rules': list(self.rules.values())}


class ScriptRuleTests(unittest.TestCase):
    def run_script(self, k, code):
        def mb_time(method='status', params=None):
            return k.call(f'time.{method}', **(params or {}))

        def mb_call(method, params=None):
            return k.call(method, **(params or {}))
        with mock.patch.object(scripts, '_tools', return_value={'mb_time': mb_time, 'mb_call': mb_call}), mock.patch.object(scripts, 'kernel', lambda: k):
            return scripts.mb_run(code=code)

    def test_rules_a_script_changed_are_reported_and_left_as_they_are(self):
        k = RuleKernel()
        out = self.run_script(k, 'def main():\n mb_call("time.rules", {"remove": ["air_below"]})\n'
                                 ' mb_call("time.rules", {"set": [{"name": "hostile_within", "kind": "hostile_within", "level": 12}, {"name": "eat", "kind": "food_below", "level": 16}]})')
        self.assertEqual(out['rulesChanged'], ['air_below', 'eat'])
        self.assertNotIn('air_below', k.rules)

    def test_no_change_no_read_and_a_rule_put_back_is_not_reported(self):
        k = RuleKernel()
        self.assertNotIn('rulesChanged', self.run_script(k, 'def main():\n mb_time("status")'))
        self.assertEqual(k.reads, 0)
        out = self.run_script(k, 'def main():\n mb_call("time.rules", {"remove": ["air_below"]})\n'
                                 ' mb_call("time.rules", {"set": [{"name": "air_below", "kind": "air_below", "level": 180}]})')
        self.assertNotIn('rulesChanged', out)
        for name, args, changes in (('mb_reflex', (), True), ('mb_interrupt', ('add',), True), ('mb_time', ('configure', {'airBelow': -1}), True),
                                    ('mb_time', ('status',), False), ('mb_hold', ({'id': 'x'},), False)):
            self.assertEqual(scripts._rule_call(name, args, {}), changes, name)  # the old names change rules too


    def test_a_tool_called_with_a_dict_first_is_not_mistaken_for_a_configure(self):
        k = RuleKernel()
        def mb_hold(item=None, slot=None):
            return {'held': item}
        with mock.patch.object(scripts, '_tools', return_value={'mb_hold': mb_hold}), mock.patch.object(scripts, 'kernel', lambda: k):
            out = scripts.mb_run(code='def main():\n return mb_hold({"id": "IC2:itemTreetap"}, slot=1)')
        self.assertEqual(out['result'], {'held': {'id': 'IC2:itemTreetap'}})


if __name__ == '__main__':
    unittest.main()
