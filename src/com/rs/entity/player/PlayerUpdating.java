package com.rs.entity.player;

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

import com.rs.WorldHandler;
import com.rs.entity.Position;
import com.rs.net.StreamBuffer;
import com.rs.util.EquipmentHelper;
import com.rs.util.Misc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Provides static utility methods for updating players.
 *
 * @author blakeman8192
 */
public final class PlayerUpdating {

    private static final int APPEARANCE_BUFFER_SIZE = 56;

    private static int blockSize(Player player) {
        if (player.getStage() != Client.Stage.LOGGED_IN)
            return 0;
        return APPEARANCE_BUFFER_SIZE + 11
                + (player.isForceChatUpdateRequired() ? player.getForceChatText().length() : 0)
                + (player.isChatUpdateRequired() ? 5 + player.getChatText().length : 0);
    }

    /**
     * Updates the player.
     *
     * @param player the player
     */
    public static void update(Player player) {
        // Calculate block buffer size
        int blockSize = blockSize(player);

        for (Player other : player.getPlayers()) {
            if (!other.needsPlacement() && other.isUpdateRequired()
                    && other.getPosition().isViewableFrom(player.getPosition())) {
                blockSize += blockSize(other);
            }
        }

        // Find other players in region
        List<Player> othersFromRegion = new ArrayList<>();

        for (int i = 0; i < WorldHandler.getInstance().getPlayers().length; i++) {
            if (player.getPlayers().size() + othersFromRegion.size() >= 255) {
                break; // Local player limit has been reached.
            }
            Player other = WorldHandler.getInstance().getPlayers()[i];

            if (other == null || other == player || other.getStage() != Client.Stage.LOGGED_IN) {
                continue;
            }

            if (!player.getPlayers().contains(other) && other.getPosition().isViewableFrom(player.getPosition())) {
                othersFromRegion.add(other);
                blockSize += blockSize(other);
            }
        }

        // XXX: The buffer sizes may need to be tuned.
        StreamBuffer.WriteBuffer out = StreamBuffer.createWriteBuffer(1024 + blockSize);
        StreamBuffer.WriteBuffer block = StreamBuffer.createWriteBuffer(blockSize);

        // Initialize the update packet.
        out.writeVariableShortPacketHeader(player.getEncryptor(), 81);
        out.setAccessType(StreamBuffer.AccessType.BIT_ACCESS);

        // Update this player.
        PlayerUpdating.updateLocalPlayerMovement(player, out);

        if (player.isUpdateRequired()) {
            PlayerUpdating.updateState(player, block, false, true);
        }

        // Update other local players.
        out.writeBits(8, player.getPlayers().size());

        for (Iterator<Player> i = player.getPlayers().iterator(); i.hasNext(); ) {
            Player other = i.next();

            if (other.getPosition().isViewableFrom(player.getPosition()) && other.getStage() == Client.Stage.LOGGED_IN
                    && !other.needsPlacement()) {
                PlayerUpdating.updateOtherPlayerMovement(other, out);

                if (other.isUpdateRequired()) {
                    boolean ignored = player.getAttributes().isIgnored(Misc.encodeBase37(other.getAttributes().getUsername()));
                    PlayerUpdating.updateState(other, block, false, ignored);
                }
            } else {
                out.writeBit(true);
                out.writeBits(2, 3);
                i.remove();
            }
        }

        // Update the local player list.
        for (Player other : othersFromRegion) {
            boolean ignored = player.getAttributes().isIgnored(Misc.encodeBase37(other.getAttributes().getUsername()));
            player.getPlayers().add(other);
            PlayerUpdating.addPlayer(out, player, other);
            PlayerUpdating.updateState(other, block, true, ignored);
        }

        // Append the attributes block to the main packet.
        if (block.getBuffer().position() > 0) {
            out.writeBits(11, 2047);
            out.setAccessType(StreamBuffer.AccessType.BYTE_ACCESS);
            out.writeBytes(block.getBuffer());
        } else {
            out.setAccessType(StreamBuffer.AccessType.BYTE_ACCESS);
        }

        // Finish the packet and send it.
        out.finishVariableShortPacketHeader();
        player.send(out.getBuffer());
    }

    /**
     * Appends the state of a player's appearance to a buffer.
     *
     * @param player the player
     * @param out    the buffer
     */
    public static void appendAppearance(Player player, StreamBuffer.WriteBuffer out) {
        PlayerAttributes attributes = player.getAttributes();
        StreamBuffer.WriteBuffer block = StreamBuffer.createWriteBuffer(APPEARANCE_BUFFER_SIZE);

        block.writeByte(player.getAttributes().getGender()); // Gender
        block.writeByte(0); // Skull icon

        // Player models
        int[] e = attributes.getEquipment();

        // Hat.
        if (e[EquipmentHelper.EQUIPMENT_SLOT_HEAD] > 1) {
            block.writeShort(0x200 + e[EquipmentHelper.EQUIPMENT_SLOT_HEAD]);
        } else {
            block.writeByte(0);
        }

        // Cape.
        if (e[EquipmentHelper.EQUIPMENT_SLOT_CAPE] > 1) {
            block.writeShort(0x200 + e[EquipmentHelper.EQUIPMENT_SLOT_CAPE]);
        } else {
            block.writeByte(0);
        }

        // Amulet.
        if (e[EquipmentHelper.EQUIPMENT_SLOT_AMULET] > 1) {
            block.writeShort(0x200 + e[EquipmentHelper.EQUIPMENT_SLOT_AMULET]);
        } else {
            block.writeByte(0);
        }

        // Weapon.
        if (e[EquipmentHelper.EQUIPMENT_SLOT_WEAPON] > 1) {
            block.writeShort(0x200 + e[EquipmentHelper.EQUIPMENT_SLOT_WEAPON]);
        } else {
            block.writeByte(0);
        }

        // Chest.
        if (e[EquipmentHelper.EQUIPMENT_SLOT_CHEST] > 1) {
            block.writeShort(0x200 + e[EquipmentHelper.EQUIPMENT_SLOT_CHEST]);
        } else {
            block.writeShort(0x100 + attributes.getAppearance()[EquipmentHelper.APPEARANCE_SLOT_CHEST]);
        }

        // Shield.
        if (e[EquipmentHelper.EQUIPMENT_SLOT_SHIELD] > 1) {
            block.writeShort(0x200 + e[EquipmentHelper.EQUIPMENT_SLOT_SHIELD]);
        } else {
            block.writeByte(0);
        }

        // Arms TODO: Check platebody/non-platebody.
        if (e[EquipmentHelper.EQUIPMENT_SLOT_CHEST] > 1) {
            block.writeShort(0x200 + e[EquipmentHelper.EQUIPMENT_SLOT_CHEST]);
        } else {
            block.writeShort(0x100 + attributes.getAppearance()[EquipmentHelper.APPEARANCE_SLOT_ARMS]);
        }

        // Legs.
        if (e[EquipmentHelper.EQUIPMENT_SLOT_LEGS] > 1) {
            block.writeShort(0x200 + e[EquipmentHelper.EQUIPMENT_SLOT_LEGS]);
        } else {
            block.writeShort(0x100 + attributes.getAppearance()[EquipmentHelper.APPEARANCE_SLOT_LEGS]);
        }

        // Head (with a hat already on).
        if (EquipmentHelper.isFullHelm(e[EquipmentHelper.EQUIPMENT_SLOT_HEAD])
                || EquipmentHelper.isFullMask(EquipmentHelper.EQUIPMENT_SLOT_HEAD)) {
            block.writeByte(0);
        } else {
            block.writeShort(0x100 + attributes.getAppearance()[EquipmentHelper.APPEARANCE_SLOT_HEAD]);
        }

        // Hands.
        if (e[EquipmentHelper.EQUIPMENT_SLOT_HANDS] > 1) {
            block.writeShort(0x200 + e[EquipmentHelper.EQUIPMENT_SLOT_HANDS]);
        } else {
            block.writeShort(0x100 + attributes.getAppearance()[EquipmentHelper.APPEARANCE_SLOT_HANDS]);
        }

        // Feet.
        if (e[EquipmentHelper.EQUIPMENT_SLOT_FEET] > 1) {
            block.writeShort(0x200 + e[EquipmentHelper.EQUIPMENT_SLOT_FEET]);
        } else {
            block.writeShort(0x100 + attributes.getAppearance()[EquipmentHelper.APPEARANCE_SLOT_FEET]);
        }

        // Beard.
        if (EquipmentHelper.isFullHelm(e[EquipmentHelper.EQUIPMENT_SLOT_HEAD])
                || EquipmentHelper.isFullMask(EquipmentHelper.EQUIPMENT_SLOT_HEAD)) {
            block.writeByte(0);
        } else {
            block.writeShort(0x100 + attributes.getAppearance()[EquipmentHelper.APPEARANCE_SLOT_BEARD]);
        }

        // Player colors
        for (int i = 0; i < attributes.getColors().length; i++) {
            block.writeByte(attributes.getColors()[i]);
        }

        // Movement animations
        block.writeShort(0x328); // stand
        block.writeShort(0x337); // stand turn
        block.writeShort(0x333); // walk
        block.writeShort(0x334); // turn 180
        block.writeShort(0x335); // turn 90 cw
        block.writeShort(0x336); // turn 90 ccw
        block.writeShort(0x338); // run

        block.writeLong(player.getUsername());
        block.writeByte(attributes.getCombatLevel());
        block.writeShort(attributes.getTotalLevel());

        // Append the block length and the block to the packet.
        out.writeByte(block.getBuffer().position(), StreamBuffer.ValueType.C);
        out.writeBytes(block.getBuffer());
    }

    /**
     * Adds a player to the local player list of another player.
     *
     * @param out    the packet to write to
     * @param player the host player
     * @param other  the player being added
     */
    public static void addPlayer(StreamBuffer.WriteBuffer out, Player player, Player other) {
        out.writeBits(11, other.getSlot()); // Server slot.
        out.writeBit(true); // Yes, an update is required.
        out.writeBit(true); // Discard walking queue(?)

        // Write the relative position.
        Position delta = Position.delta(player.getPosition(), other.getPosition());
        out.writeBits(5, delta.getY());
        out.writeBits(5, delta.getX());
    }

    /**
     * Updates movement for this local player. The difference between this
     * method and the other player method is that this will make use of sector
     * 2,3 to place the player in a specific position while sector 2,3 is not
     * present in updating of other players (it simply flags local list removal
     * instead).
     *
     * @param player
     * @param out
     */
    public static void updateLocalPlayerMovement(Player player, StreamBuffer.WriteBuffer out) {
        boolean updateRequired = player.isUpdateRequired();

        if (player.needsPlacement()) { // Do they need placement?
            out.writeBit(true); // Yes, there is an update.
            int posX = player.getPosition().getLocalX(player.getCurrentRegion());
            int posY = player.getPosition().getLocalY(player.getCurrentRegion());
            appendPlacement(out, posX, posY, player.getPosition().getZ(), player.isResetMovementQueue(), updateRequired);
        } else { // No placement update, check for movement.
            int pDir = player.getPrimaryDirection();
            int sDir = player.getSecondaryDirection();
            updateMovement(out, pDir, sDir, updateRequired);
        }
    }

    /**
     * Updates the movement of a player for another player (does not make use of sector 2,3).
     *
     * @param player the player
     * @param out    the packet
     */
    public static void updateOtherPlayerMovement(Player player, StreamBuffer.WriteBuffer out) {
        boolean updateRequired = player.isUpdateRequired();
        int pDir = player.getPrimaryDirection();
        int sDir = player.getSecondaryDirection();
        updateMovement(out, pDir, sDir, updateRequired);
    }

    private static void updateMovement(StreamBuffer.WriteBuffer out, int pDir, int sDir, boolean updateRequired) {
        if (pDir != -1) { // If they moved.
            out.writeBit(true); // Yes, there is an update.

            if (sDir != -1) { // If they ran.
                appendRun(out, pDir, sDir, updateRequired);
            } else { // Movement but no running - they walked.
                appendWalk(out, pDir, updateRequired);
            }
        } else { // No movement.
            if (updateRequired) { // Does the state need to be updated?
                out.writeBit(true); // Yes, there is an update.
                appendStand(out);
            } else { // No update whatsoever.
                out.writeBit(false);
            }
        }
    }

    /**
     * Updates the state of a player.
     *
     * @param player the player
     * @param block  the block
     */
    public static void updateState(Player player, StreamBuffer.WriteBuffer block, boolean forceAppearance, boolean noChat) {
        // First we must prepare the mask.
        int mask = 0x0;

        if (player.isGraphicUpdateRequired()) {
            mask |= 0x100;
        }
        if (player.isAnimationUpdateRequired()) {
            mask |= 0x8;
        }
        if (player.isForceChatUpdateRequired()) {
            mask |= 0x4;
        }
        if (player.isChatUpdateRequired() && !noChat) {
            mask |= 0x80;
        }
        if (player.isAppearanceUpdateRequired() || forceAppearance) {
            mask |= 0x10;
        }

        // writing the actual mask.
        if (mask >= 0x100) {
            mask |= 0x40;
            block.writeShort(mask, StreamBuffer.ByteOrder.LITTLE);
        } else {
            block.writeByte(mask);
        }

        // Finally, we append the attributes blocks.
        // Async. walking
        // Graphics
        if (player.isGraphicUpdateRequired()) {
            appendGraphic(player, block);
        }
        // Animation
        if (player.isAnimationUpdateRequired()) {
            appendAnimation(player, block);
        }
        // Forced chat
        if (player.isForceChatUpdateRequired()) {
            appendForceChat(player, block);
        }
        // Chat
        if (player.isChatUpdateRequired() && !noChat) {
            appendChat(player, block);
        }
        // Interacting with entity
        // Appearance
        if (player.isAppearanceUpdateRequired() || forceAppearance) {
            appendAppearance(player, block);
        }
        // Face coordinates
        // Primary hit
        // Secondary hit
    }

    /**
     * Appends the state of a player's force chat to a buffer.
     *
     * @param player the player
     * @param out    the buffer
     */
    public static void appendForceChat(Player player, StreamBuffer.WriteBuffer out) {
        out.writeString(player.getForceChatText());
    }

    /**
     * Appends the state of a player's chat to a buffer.
     *
     * @param player the player
     * @param out    the buffer
     */
    public static void appendChat(Player player, StreamBuffer.WriteBuffer out) {
        out.writeShort(((player.getChatColor() & 0xff) << 8) + (player.getChatEffects() & 0xff), StreamBuffer.ByteOrder.LITTLE);
        out.writeByte(player.getAttributes().getPrivilege().toInt());
        out.writeByte(player.getChatText().length, StreamBuffer.ValueType.C);
        out.writeBytesReverse(player.getChatText());
    }

    /**
     * Appends the state of a player's attached graphics to a buffer.
     *
     * @param player the player
     * @param out    the buffer
     */
    public static void appendGraphic(Player player, StreamBuffer.WriteBuffer out) {
        out.writeShort(player.getGraphic().getId(), StreamBuffer.ByteOrder.LITTLE);
        out.writeInt(player.getGraphic().getDelay());
    }

    /**
     * Appends the state of a player's animation to a buffer.
     *
     * @param player the player
     * @param out    the buffer
     */
    public static void appendAnimation(Player player, StreamBuffer.WriteBuffer out) {
        out.writeShort(player.getAnimation().getId(), StreamBuffer.ByteOrder.LITTLE);
        out.writeByte(player.getAnimation().getDelay(), StreamBuffer.ValueType.C);
    }

    /**
     * Appends the stand version of the movement section of the update packet
     * (sector 2,0). Appending this (instead of just a zero bit) automatically
     * assumes that there is a required attribute update afterwards.
     *
     * @param out the buffer to append to
     */
    public static void appendStand(StreamBuffer.WriteBuffer out) {
        out.writeBits(2, 0); // 0 - no movement.
    }

    /**
     * Appends the walk version of the movement section of the update packet
     * (sector 2,1).
     *
     * @param out              the buffer to append to
     * @param direction        the walking direction
     * @param attributesUpdate whether or not a player attributes update is required
     */
    public static void appendWalk(StreamBuffer.WriteBuffer out, int direction, boolean attributesUpdate) {
        out.writeBits(2, 1); // 1 - walking.

        // Append the actual sector.
        out.writeBits(3, direction);
        out.writeBit(attributesUpdate);
    }

    /**
     * Appends the walk version of the movement section of the update packet
     * (sector 2,2).
     *
     * @param out              the buffer to append to
     * @param direction        the walking direction
     * @param direction2       the running direction
     * @param attributesUpdate whether or not a player attributes update is required
     */
    public static void appendRun(StreamBuffer.WriteBuffer out, int direction, int direction2, boolean attributesUpdate) {
        out.writeBits(2, 2); // 2 - running.

        // Append the actual sector.
        out.writeBits(3, direction);
        out.writeBits(3, direction2);
        out.writeBit(attributesUpdate);
    }

    /**
     * Appends the player placement version of the movement section of the
     * update packet (sector 2,3). Note that by others this was previously
     * called the "teleport update".
     *
     * @param out                  the buffer to append to
     * @param localX               the local X coordinate
     * @param localY               the local Y coordinate
     * @param z                    the Z coordinate
     * @param discardMovementQueue whether or not the client should discard the movement queue
     * @param attributesUpdate     whether or not a plater attributes update is required
     */
    public static void appendPlacement(StreamBuffer.WriteBuffer out, int localX, int localY, int z, boolean discardMovementQueue, boolean attributesUpdate) {
        out.writeBits(2, 3); // 3 - placement.

        // Append the actual sector.
        out.writeBits(2, z);
        out.writeBit(discardMovementQueue);
        out.writeBit(attributesUpdate);
        out.writeBits(7, localY);
        out.writeBits(7, localX);
    }

}
