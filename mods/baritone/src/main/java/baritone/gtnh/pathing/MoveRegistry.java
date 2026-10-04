// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.Moves;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The moves a route search may use: the walker's own, and those added here. Every job that walks (goto, follow, mine,
 * build, click, fight) plans with the same search, so a move added here is one all of them may take.
 *
 * An added kind of movement is a Source. At the start of each search it is asked, on the game thread, which moves this
 * body has now: it looks at the player (what is worn, held, charged) and answers with moves that carry those facts, or
 * with none. The search thread never looks at the player. See docs/MOVEMENTS.md.
 */
public final class MoveRegistry {
    public interface Source {
        /** The moves this body has now; an empty list when it has none. Game thread. */
        List<Move> moves(IPlayerContext player);
    }
    private static final Move[] OWN=Moves.values();
    private static final Map<String,Source> SOURCES=new LinkedHashMap<>();
    private MoveRegistry(){}

    /** Adds a kind of movement under a name; the same name again replaces it. */
    public static synchronized void register(String name,Source source){SOURCES.put(name,source);}
    public static synchronized void unregister(String name){SOURCES.remove(name);}
    public static synchronized List<String> names(){return List.copyOf(SOURCES.keySet());}
    /** The walker's own moves and nothing else. */
    public static Move[] own(){return OWN;}
    /** The walker's own moves, then what each source has for this body now. Game thread. */
    public static synchronized Move[] capture(IPlayerContext player){
        if(SOURCES.isEmpty())return OWN;
        List<Move> all=new ArrayList<>(List.of(OWN));
        for(Source source:SOURCES.values())all.addAll(source.moves(player));
        return all.toArray(new Move[0]);
    }
    /** These moves after the walker's own. */
    public static Move[] with(Move... added){
        List<Move> all=new ArrayList<>(List.of(OWN));all.addAll(List.of(added));return all.toArray(new Move[0]);
    }
}
