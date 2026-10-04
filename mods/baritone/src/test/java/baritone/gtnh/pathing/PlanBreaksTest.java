// SPDX-License-Identifier: LGPL-3.0-or-later
package baritone.gtnh.pathing;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlanBreaksTest {
    /** The rule before this class existed: replaceExisting decided alone, so a finished cell was breakable transit. */
    private static boolean before(boolean clear,boolean correct,boolean deferred,boolean replace,boolean pending){
        if(deferred)return false;
        if(!clear&&!replace)return false;
        return !pending||correct;
    }
    @Test public void aFinishedSolidCellIsNeverBrokenWhateverReplaceSays(){
        for(boolean replace:new boolean[]{false,true})for(boolean pending:new boolean[]{false,true})
            assertFalse(PlanBreaks.allowed(false,true,false,replace,pending));
        assertTrue("this is the case that looped",before(false,true,false,true,false));
    }
    @Test public void everyOtherCaseIsUnchanged(){
        boolean[] values={false,true};int same=0;
        for(boolean clear:values)for(boolean correct:values)for(boolean deferred:values)for(boolean replace:values)for(boolean pending:values){
            if(!clear&&correct&&!deferred&&replace)continue;
            assertEquals(before(clear,correct,deferred,replace,pending),PlanBreaks.allowed(clear,correct,deferred,replace,pending));same++;
        }
        assertEquals(30,same);
    }
    @Test public void whatAJobStillBreaks(){
        assertTrue("a wrong block in a solid cell, when the job replaces",PlanBreaks.allowed(false,false,false,true,false));
        assertFalse("but not without replaceExisting",PlanBreaks.allowed(false,false,false,false,false));
        assertTrue("whatever fills a cell the plan wants empty",PlanBreaks.allowed(true,false,false,false,false));
        assertFalse("an access support in a deferred-air cell before cleanup",PlanBreaks.allowed(true,false,true,true,false));
        assertFalse("a block this job just clicked in and has not seen match",PlanBreaks.allowed(false,false,false,true,true));
    }
}
