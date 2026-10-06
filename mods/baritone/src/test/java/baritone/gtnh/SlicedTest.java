// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SlicedTest {
    private final Sliced<Integer,String> goals=new Sliced<>();
    private final int[] made=new int[100];
    private int clock;
    /** One tick of ten makes (each costs a tenth of the slice), asked in the order given, as the adapter asks. */
    private List<String> tick(Iterable<Integer> keys){
        List<String> served=new ArrayList<>();clock++;goals.tick(1000);
        for(int k:keys){
            String value=goals.fresh(k);
            if(value==null){
                if(goals.turn(k)){made[k]++;value="made "+clock;goals.put(k,value);goals.spent(100);}
                else value=goals.last(k,"source");
            }
            served.add(value);
        }
        return served;
    }
    private static List<Integer> keys(int from,int to){List<Integer> out=new ArrayList<>();for(int k=from;k<to;k++)out.add(k);return out;}
    @Test public void aTickMakesNoMoreThanItsSliceAndServesTheRestAsTheyWere(){
        var first=tick(keys(0,100));
        assertEquals(Collections.nCopies(10,"made 1"),first.subList(0,10));assertEquals(Collections.nCopies(90,"source"),first.subList(10,100));
        // Nothing changed: what is made stays, and the next ten are made.
        var second=tick(keys(0,100));
        assertEquals(Collections.nCopies(10,"made 1"),second.subList(0,10));assertEquals(Collections.nCopies(10,"made 2"),second.subList(10,20));
        // A change: the old value is served until its turn comes, and the turn of what was never made comes first.
        goals.age();var third=tick(keys(0,100));
        assertEquals(Collections.nCopies(10,"made 1"),third.subList(0,10));assertEquals(Collections.nCopies(10,"made 3"),third.subList(20,30));
        goals.clear();assertEquals(Collections.nCopies(90,"source"),tick(keys(0,100)).subList(10,100));
    }
    @Test public void withAChangeEveryTickEveryKeyStillHasItsTurn(){
        for(int t=0;t<100;t++){goals.age();tick(keys(0,100));}
        // Asked in the same order every tick, with all of them old every tick: ten ticks a round, not the first ten for ever.
        assertTrue("fewest "+Arrays.stream(made).min().getAsInt()+", most "+Arrays.stream(made).max().getAsInt(),Arrays.stream(made).min().getAsInt()>=9&&Arrays.stream(made).max().getAsInt()<=11);
    }
    @Test public void aKeyNoLongerAskedForHoldsNoTurn(){
        goals.age();tick(keys(0,100));                    // 0..9 made, 10..99 refused, never made
        goals.age();tick(keys(0,10));                     // those that wait are not asked for: 0..9 wait behind them this tick
        assertEquals(1,made[0]);
        tick(keys(0,10));assertEquals(2,made[0]);
    }
}
