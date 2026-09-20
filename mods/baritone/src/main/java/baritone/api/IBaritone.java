// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.api;

import baritone.api.utils.IPlayerContext;
import baritone.behavior.LookBehavior;
import baritone.behavior.PathingBehavior;
import baritone.utils.InputOverrideHandler;

/** Native 1.7 process API. Types use the metadata-preserving compatibility model. */
public interface IBaritone {
    IPlayerContext getPlayerContext();
    LookBehavior getLookBehavior();
    PathingBehavior getPathingBehavior();
    InputOverrideHandler getInputOverrideHandler();
    default baritone.api.process.IFollowProcess getFollowProcess(){throw new UnsupportedOperationException("FollowProcess unavailable in this context");}
    default baritone.api.process.IMineProcess getMineProcess(){throw new UnsupportedOperationException("MineProcess unavailable in this context");}
    default baritone.api.process.IBuilderProcess getBuilderProcess(){throw new UnsupportedOperationException("BuilderProcess unavailable in this context");}
    default baritone.api.process.IExploreProcess getExploreProcess(){throw new UnsupportedOperationException("ExploreProcess unavailable in this context");}
    default baritone.api.process.IFarmProcess getFarmProcess(){throw new UnsupportedOperationException("FarmProcess unavailable in this context");}
    default baritone.api.process.ICustomGoalProcess getCustomGoalProcess(){throw new UnsupportedOperationException("CustomGoalProcess unavailable in this context");}
    default baritone.api.process.IGetToBlockProcess getGetToBlockProcess(){throw new UnsupportedOperationException("GetToBlockProcess unavailable in this context");}
    default baritone.api.cache.IWorldProvider getWorldProvider(){throw new UnsupportedOperationException("WorldProvider unavailable in this context");}
    default baritone.api.pathing.calc.IPathingControlManager getPathingControlManager(){throw new UnsupportedOperationException("PathingControlManager unavailable in this context");}
    default baritone.api.event.listener.IEventBus getGameEventHandler(){throw new UnsupportedOperationException("GameEventHandler unavailable in this context");}
    default baritone.api.selection.ISelectionManager getSelectionManager(){throw new UnsupportedOperationException("SelectionManager unavailable in this context");}
}
