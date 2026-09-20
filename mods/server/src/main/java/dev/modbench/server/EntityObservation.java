// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.server;

import com.google.gson.JsonObject;
import dev.modbench.bridge.Json;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayerMP;

/** Durable identities are supplied by the server: 1.7 clients do not sync mob UUIDs. */
final class EntityObservation {
    static JsonObject read(EntityPlayerMP player,JsonObject params) {
        Entity found=null;
        if(params.has("uuid")) {
            UUID uuid=UUID.fromString(params.get("uuid").getAsString());
            for(Object value:player.worldObj.loadedEntityList) {
                Entity entity=(Entity)value;if(entity.getUniqueID().equals(uuid)) {found=entity;break;}
            }
        } else {
            found=player.worldObj.getEntityByID(Json.integer(params,"entityId",-1,0,Integer.MAX_VALUE));
            if(found!=null) {
                String type=found instanceof net.minecraft.entity.player.EntityPlayer?"player":EntityList.getEntityString(found);
                String expected=params.has("entityType")&&!params.get("entityType").isJsonNull()?params.get("entityType").getAsString():null;
                if(!java.util.Objects.equals(type,expected)) throw new IllegalArgumentException("entity identity changed; observe again");
            }
        }
        if(found==null||found.isDead||found.getDistanceSqToEntity(player)>128*128)
            return Json.object("found",false,"status","not_observed","dimension",player.dimension);
        return Json.object("found",true,"entityId",found.getEntityId(),"uuid",found.getUniqueID().toString(),"uuidScope","server",
            "type",EntityList.getEntityString(found),"name",found.getCommandSenderName(),"dimension",player.dimension,
            "pos",Json.array(found.posX,found.boundingBox.minY,found.posZ),"worldId",WorldIdentity.get(player.mcServer.worldServers[0]));
    }
}
