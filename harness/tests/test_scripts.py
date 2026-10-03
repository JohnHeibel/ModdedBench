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

    def test_a_guard_refusal_cannot_be_swallowed_by_except_exception(self):
        def act():
            raise BridgeError('bad_request', 'interrupt_latched: 1 delivered, this action was not started', 'act.input')
        out = self.run_script('def main():\n try:\n  act()\n except Exception:\n  return "carried on"', act=act)
        self.assertNotIn('result', out)
        self.assertEqual(out['stopped'], 'BridgeError: act.input: bad_request: interrupt_latched: 1 delivered, this action was not started')
        self.assertEqual((out['line'], out['interrupted']['tool']), (3, 'act'))

    def test_a_wrapped_pause_still_counts_and_a_bare_except_is_reported(self):
        def craft():
            try:
                raise BridgeError('cancelled', 'world paused by a guard (threat): read mb_time status, decide, resume', 'gui.click_slot')
            except BridgeError as error:
                raise RuntimeError('screen changed') from error
        out = self.run_script('def main():\n try:\n  craft()\n except:\n  pass\n return 1', craft=craft)
        self.assertEqual(out['result'], 1)
        self.assertEqual(out['interrupted'], {'tool': 'craft', 'error': 'screen changed'})

    def test_ordinary_errors_stay_catchable(self):
        def act():
            raise ValueError('no such item')
        out = self.run_script('def main():\n try:\n  act()\n except Exception as e:\n  return str(e)', act=act)
        self.assertEqual(out, {'result': 'no such item', 'log': []})


class GuardKernel:
    def __init__(self):
        self.conditions, self.reads = dict(healthDrop=True, airBelow=180, threatWithin=12), 0

    def call(self, method, timeout=None, **p):
        if method == 'time.status':
            self.reads += 1
            return {'state': {'conditions': dict(self.conditions)}}
        self.conditions.update(p)
        return {}


class ScriptGuardTests(unittest.TestCase):
    def run_script(self, k, code):
        def mb_time(method='status', params=None):
            return k.call(f'time.{method}', **(params or {}))

        def mb_call(method, params=None):
            return k.call(method, **(params or {}))
        with mock.patch.object(scripts, '_tools', return_value={'mb_time': mb_time, 'mb_call': mb_call}), mock.patch.object(scripts, 'kernel', lambda: k):
            return scripts.mb_run(code=code)

    def test_guards_a_script_changed_are_reported_and_left_as_they_are(self):
        k = GuardKernel()
        out = self.run_script(k, 'def main():\n mb_time("configure", {"airBelow": -1})\n mb_call("time.configure", {"threatWithin": -1, "healthDrop": True})')
        self.assertEqual(out['guardsChanged'], {'airBelow': [180, -1], 'threatWithin': [12, -1]})
        self.assertEqual(k.conditions['airBelow'], -1)

    def test_no_configure_no_read_and_a_restored_guard_is_not_reported(self):
        k = GuardKernel()
        self.assertNotIn('guardsChanged', self.run_script(k, 'def main():\n mb_time("status")'))
        self.assertEqual(k.reads, 1)  # the script's own status call only
        out = self.run_script(k, 'def main():\n mb_time("configure", {"airBelow": -1})\n mb_time("configure", {"airBelow": 180})')
        self.assertNotIn('guardsChanged', out)


    def test_a_tool_called_with_a_dict_first_is_not_mistaken_for_a_configure(self):
        k = GuardKernel()
        def mb_hold(item=None, slot=None):
            return {'held': item}
        with mock.patch.object(scripts, '_tools', return_value={'mb_hold': mb_hold}), mock.patch.object(scripts, 'kernel', lambda: k):
            out = scripts.mb_run(code='def main():\n return mb_hold({"id": "IC2:itemTreetap"}, slot=1)')
        self.assertEqual(out['result'], {'held': {'id': 'IC2:itemTreetap'}})


if __name__ == '__main__':
    unittest.main()
