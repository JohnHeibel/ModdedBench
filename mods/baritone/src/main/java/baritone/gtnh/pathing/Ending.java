// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import java.util.function.BooleanSupplier;

/**
 * How a job ends when it has something to put back first (blocks it removed to see a click, scaffold it left): the
 * first ending is kept and the job runs on, closing; whatever ends the closing, done or cut short, the job reports
 * the first ending. Pure: the job asks, and says what it has to put back.
 */
public final class Ending {
    /** The reason a job gives when its closing is complete. */
    public static final String DONE="closed";
    private String terminal,reason,cut="";
    public boolean open(){return terminal!=null;}
    /**
     * The job is ending as (terminal, reason). True: it closes first and keeps running. work is asked once, at the
     * first ending: whether the job still has its controls and something to put back. An ending while closing is the
     * end, and anything but DONE is what cut the closing short.
     */
    public boolean defer(String terminal,String reason,BooleanSupplier work) {
        if(open()){if(!reason.equals(DONE))cut=reason;return false;}
        if(!work.getAsBoolean())return false;
        this.terminal=terminal;this.reason=reason;return true;
    }
    public String terminal(String otherwise){return open()?terminal:otherwise;}
    public String reason(String otherwise){return open()?reason:otherwise;}
    public String cut(){return cut;}
}
