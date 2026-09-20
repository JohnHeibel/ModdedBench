// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;
public record Tuple<A,B>(A first,B second) { public A getFirst(){return first;} public B getSecond(){return second;} }
