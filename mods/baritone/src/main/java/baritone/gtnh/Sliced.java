// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import java.util.HashMap;
import java.util.Map;

/**
 * Values that cost the game thread to make, asked for by key every tick, made a slice a tick. age() says that what
 * they were made from changed: each is made again in its turn and is served as it was until then, so nothing is made
 * twice for one change and no tick makes them all. The asker decides the order of asking, so turns go by age: what was
 * refused last tick comes first, and nothing made later than the oldest of those is made before it.
 */
final class Sliced<K,V> {
    private record Made<V>(V value,int age){}
    private final Map<K,Made<V>> made=new HashMap<>();
    private int now,first=Integer.MAX_VALUE,refused=Integer.MAX_VALUE;
    private long left;
    /** A tick begins, with this long to make values in. */
    void tick(long ns){left=ns;first=refused;refused=Integer.MAX_VALUE;}
    void age(){now++;}
    /** Nothing made so far may be served. */
    void clear(){made.clear();}
    /** The value made since the last age(), or null. */
    V fresh(K key){var m=made.get(key);return m!=null&&m.age==now?m.value:null;}
    /** Whether this key's value may be made now. The time it takes is then handed to spent(). */
    boolean turn(K key){
        var m=made.get(key);int age=m==null?-1:m.age;
        if(left>0&&age<=first)return true;
        refused=Math.min(refused,age);return false;
    }
    void spent(long ns){left-=ns;}
    /** The value as last made, however old. */
    V last(K key,V otherwise){var m=made.get(key);return m==null?otherwise:m.value;}
    void put(K key,V value){made.put(key,new Made<>(value,now));}
}
