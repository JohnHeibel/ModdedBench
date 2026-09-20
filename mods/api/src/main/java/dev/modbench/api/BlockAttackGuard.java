// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.api;

/** A held attack must not continue into another block after its target disappears. */
public record BlockAttackGuard(int x,int y,int z,Object block,int metadata) {
    public boolean unchanged(Object current,int meta) {return block==current&&metadata==meta;}
    public boolean permits(int hitX,int hitY,int hitZ,Object current,int meta) {
        return x==hitX&&y==hitY&&z==hitZ&&unchanged(current,meta);
    }
}
