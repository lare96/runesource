package com.rs.plugin;
/*
 * This file is part of RuneSource.
 *
 * RuneSource is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RuneSource is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RuneSource.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.rs.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A bridge between invokable plugins and the core of the server.
 *
 * @author Pure_
 */
public final class PluginBridge {

    private static HashMap<String, List<String>> bindings = new HashMap<>();
    public static final String ACTION_BUTTON_HANDLER_EVENT = "onActionButton";
    public static final String COMMAND_HANDLER_EVENT = "onCommand";

    /**
     * Registers a binding.
     *
     * @param event event name
     * @param pluginName plugin name
     */
    public static void registerEvent(String event, String pluginName) {
        if (bindings.containsKey(event)) {
            bindings.get(event).add(pluginName);
        } else {
            List<String> pluginNames = new ArrayList<>();
            pluginNames.add(pluginName);
            bindings.put(event, pluginNames);
        }
    }

    public static boolean triggerCommand(Player player, String keyword, String[] args) {
        if (!bindings.containsKey(COMMAND_HANDLER_EVENT)) {
            return false;
        }

        for (String pluginName : bindings.get(COMMAND_HANDLER_EVENT)) {
            PluginHandler.invokeMethod(pluginName, COMMAND_HANDLER_EVENT, player, keyword, args);
        }
        return true;
    }

    public static boolean triggerActionButton(Player player, int actionButtonId) {
        if (!bindings.containsKey(ACTION_BUTTON_HANDLER_EVENT)) {
            return false;
        }

        for (String pluginName : bindings.get(ACTION_BUTTON_HANDLER_EVENT)) {
            PluginHandler.invokeMethod(pluginName, ACTION_BUTTON_HANDLER_EVENT, player, actionButtonId);
        }
        return true;
    }
}
