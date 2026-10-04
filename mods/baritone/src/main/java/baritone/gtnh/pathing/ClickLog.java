// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import java.util.*;

/**
 * What a job knows of its clicks, as its journal keeps it: one word per click, by the click's key (StepPlan.Step.key).
 *   verified         the block stands as asked, and what was expected was read
 *   unverified       made, and nothing the job can read confirms what the click set
 *   already_present  the cell held the block before the job came to it
 *   used             a use without an expect
 *   expect_failed    made; what was expected was not read (the job stopped there)
 *   gui_opened       a use that opened a screen (the job closed it and stopped there)
 *   unknown          a use that may or may not have happened before a restart (the job stopped there)
 *   taking           use is about to be pressed: written before the press, replaced by what came of it
 * Every word but the last is settled: a later session does not make that click again. Pure.
 */
public final class ClickLog {
    public static final String TAKING="taking";
    private static final Set<String> UNVERIFIED=Set.of("unverified","expect_failed","gui_opened","unknown");
    /** The journal's own map: what is put here is what the next checkpoint writes. */
    public final Map<String,String> results=new LinkedHashMap<>();
    /** Clicks a session before this one was taking when it ended without saying what came of them. */
    private final Set<String> interrupted=new HashSet<>();
    public ClickLog(Map<String,Object> saved) {
        saved.forEach((k,v)->results.put(k,String.valueOf(v)));
        results.forEach((k,v)->{if(v.equals(TAKING))interrupted.add(k);});
    }
    public String of(String key){return results.get(key);}
    public boolean settled(String key){String r=results.get(key);return r!=null&&!r.equals(TAKING);}
    public boolean taking(String key){return TAKING.equals(results.get(key));}
    /** Nobody knows whether this click happened: it is not made again, and the job stops on it once to say so. */
    public boolean interrupted(String key){return interrupted.contains(key)&&taking(key);}
    public void record(String key,String result){results.put(key,result);}
    public void drop(String key){results.remove(key);}
    /** Receipt: how many clicks are settled, and how. done also counts the uses that had nothing to check. */
    public Map<String,Object> counts() {
        int done=0,verified=0,unverified=0,present=0;
        for(String r:results.values()){if(r.equals(TAKING))continue;done++;if(r.equals("verified"))verified++;else if(r.equals("already_present"))present++;else if(UNVERIFIED.contains(r))unverified++;}
        Map<String,Object> out=new LinkedHashMap<>();out.put("done",done);out.put("verified",verified);out.put("unverified",unverified);out.put("alreadyPresent",present);return out;
    }
    public boolean unverified(String key){String r=results.get(key);return r!=null&&UNVERIFIED.contains(r);}
    /**
     * The reason a job stops for when the stall watchdog trips during a click: each phase's own, so a stuck click
     * says what it was stuck at. A phase with no reason of its own is a plain stall, with the click's position.
     */
    public static String stalled(String phase) {
        return switch(phase) {
            case "moving"->"no_route_from_here";
            case "aim"->"aim_mismatch";
            case "break","fetch"->"access_failed";
            case "click","settle"->"placement_rejected";
            case "expect_before","expect"->"expect_timeout";
            default->"stalled";
        };
    }
}
