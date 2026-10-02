import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcp'))
import mbtool
from kernel import Kernel, Reply, resume_once
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


if __name__ == '__main__':
    unittest.main()
